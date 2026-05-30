package com.travelplus.task;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.travelplus.entity.TicketOrder;
import com.travelplus.mapper.TicketOrderMapper;
import com.travelplus.service.ITicketOrderService;
import com.travelplus.utils.RedisConstants;
import com.travelplus.utils.SystemConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 【订单超时的实现】秒杀订单支付超时自动取消定时任务。
 * <p>
 * 实现细节：
 * - 定时策略：{@link Scheduled}(fixedDelay=60s, initialDelay=10s)——应用启动 10s 后首次扫描，
 *   之后每 60 秒扫描一次（fixedDelay=上次执行完成后再延时，避免任务执行时间过长时重叠运行）；
 *   需配合 TravelPlusApplication 上的 @EnableScheduling 开启定时任务支持。
 * - 扫描口径：SELECT id,user_id,ticket_id FROM tb_ticket_order WHERE status=1
 *   AND create_time &lt; NOW()-15 分钟。超时判定全部在 SQL 内完成（create_time &lt;
 *   DATE_SUB(NOW(), INTERVAL PAY_TIMEOUT_MINUTES MINUTE)），不用应用侧 LocalDateTime 计算 cutoff——
 *   DB 会话时区（JDBC serverTimezone=UTC，create_time 列存 UTC 时间）与 JVM（UTC+8）不同源，
 *   应用侧 now() 与 DB 时间差 8 小时，会把刚创建的订单误判为超时取消；SQL 内 NOW() 与 create_time
 *   同源（均为 DB 会话时区），语义正确，且不依赖部署环境的时区设置。
 * - 单订单处理（委托 {@link ITicketOrderService#cancelExpiredOrder}，@Transactional）：
 *   ① 条件更新 status=1→4（affected=0 说明已被其他流程处理，幂等跳过）；
 *   ② DB 库存回增 tb_seckill_ticket.stock+1（与①同一事务，DB 侧原子）；
 *   ③ 事务提交后 Redis 库存回增 INCR seckill:stock:{ticketId}（与 seckill.lua 预检扣减的 key 一致）；
 *   ④ Redis 释放下单资格 SREM seckill:order:{ticketId} {userId}——用户可重新秒杀；
 *   ⑤ 日志记录超时取消明细（订单id/用户id/票id）。
 * - 并发与幂等：条件更新保证同一订单只被取消一次；@Scheduled 默认单实例执行（本项目单机部署）。
 *   生产多实例部署时需在此改造为分布式锁（如 Redisson RLock）避免多实例重复扫描处理，本项目不引入。
 *
 * @author TravelPlus
 * @since 2026-08-12
 */
@Slf4j
@Component
public class OrderTimeoutTask {

    @Resource
    private TicketOrderMapper ticketOrderMapper;
    @Resource
    private ITicketOrderService ticketOrderService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 【订单超时的实现】每 60 秒扫描并取消超时未支付订单
     * <p>
     * 时区口径：超时判定用 SQL 内 NOW() 比较（create_time &lt; DATE_SUB(NOW(), INTERVAL
     * PAY_TIMEOUT_MINUTES MINUTE)），时间源为 DB 会话时区（UTC），与 create_time 列同源——
     * 若改用应用侧 LocalDateTime.now()（JVM UTC+8）计算 cutoff，会与 DB 的 UTC 时间差 8 小时，
     * 把刚创建的新订单误判为超时取消（见类注释）。SQL 内比较与部署环境时区无关，语义稳定。
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 10_000)
    public void scanExpiredOrders() {
        // 1.扫描超时未支付订单（只取处理所需的 id/user_id/ticket_id 三列）。
        //   超时判定放 SQL 内：create_time < NOW()-PAY_TIMEOUT_MINUTES 分钟，时间源与 create_time
        //   同为 DB 会话时区（UTC），避免应用侧 LocalDateTime（JVM UTC+8）与 DB 差 8 小时误杀新订单。
        List<TicketOrder> expiredOrders = ticketOrderMapper.selectList(
                new QueryWrapper<TicketOrder>()
                        .select("id", "user_id", "ticket_id")
                        .eq("status", 1)
                        .apply("create_time < DATE_SUB(NOW(), INTERVAL " + SystemConstants.PAY_TIMEOUT_MINUTES + " MINUTE)"));
        if (expiredOrders.isEmpty()) {
            return;
        }
        log.info("订单超时扫描: 发现 {} 条超时未支付订单（超过 {} 分钟未支付）", expiredOrders.size(),
                SystemConstants.PAY_TIMEOUT_MINUTES);
        // 2.逐条取消：DB（条件更新+库存回增，事务）→ Redis（库存回增+释放下单资格）
        for (TicketOrder order : expiredOrders) {
            try {
                processTimeoutOrder(order);
            } catch (Exception e) {
                // 单条失败不影响其他订单，记录日志后继续扫描
                log.error("订单超时处理失败: 订单id={}, 原因={}", order.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 【订单超时的实现】取消单条超时订单：DB 取消+回补库存（@Transactional）→ Redis 回增库存+释放秒杀资格
     *
     * @param order 已由扫描查出的超时未支付订单（id/userId/ticketId 已填充）
     */
    private void processTimeoutOrder(TicketOrder order) {
        Long orderId = order.getId();
        Long userId = order.getUserId();
        Long ticketId = order.getTicketId();
        // 1.DB 侧取消 + 库存回增（条件更新保证幂等，返回 false=已被其他流程处理，跳过后续 Redis 操作）
        boolean cancelled = ticketOrderService.cancelExpiredOrder(orderId, userId, ticketId);
        if (!cancelled) {
            return;
        }
        // 2.Redis 库存回增：key=seckill:stock:{ticketId}，与 seckill.lua 预检 INCRBY -1 扣减的 key 一致
        stringRedisTemplate.opsForValue().increment(RedisConstants.SECKILL_STOCK_KEY + ticketId);
        // 3.Redis 释放下单资格：SREM seckill:order:{ticketId} {userId}，用户可重新秒杀
        stringRedisTemplate.opsForSet().remove(RedisConstants.SECKILL_ORDER_KEY + ticketId, userId.toString());
        log.info("订单超时自动取消: 订单id={}, 用户id={}, 票id={}, 已回补DB库存+Redis库存(seckill:stock:{})并释放秒杀资格(seckill:order:{})",
                orderId, userId, ticketId, ticketId, ticketId);
    }
}
