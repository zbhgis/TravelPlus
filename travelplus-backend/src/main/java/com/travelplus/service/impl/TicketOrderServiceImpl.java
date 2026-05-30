package com.travelplus.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.Result;
import com.travelplus.entity.Attraction;
import com.travelplus.entity.BalanceRecord;
import com.travelplus.entity.PointsRecord;
import com.travelplus.entity.Ticket;
import com.travelplus.entity.TicketOrder;
import com.travelplus.entity.User;
import com.travelplus.mapper.AttractionMapper;
import com.travelplus.mapper.BalanceRecordMapper;
import com.travelplus.mapper.PointsRecordMapper;
import com.travelplus.mapper.TicketMapper;
import com.travelplus.mapper.TicketOrderMapper;
import com.travelplus.mapper.UserMapper;
import com.travelplus.service.ISeckillTicketService;
import com.travelplus.service.ITicketOrderService;
import com.travelplus.utils.RedisIdWorker;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 【秒杀功能的实现】秒杀订单服务实现：Lua 预检 → Kafka 异步落库 → 返回订单id 的完整秒杀下单链路
 * <p>
 * 实现细节：
 * - 入口 seckillTicket：Redis 执行 seckill.lua 原子预检（seckill:stock:{ticketId} 库存、
 *   seckill:order:{ticketId} 一人一单），通过后构造订单发 Kafka topic=seckill-ticket-order
 *   （JSON 字符串），请求立即返回订单 id，库存扣减与建单异步完成，削峰抗高并发。
 * - 消费端 SeckillTicketConsumer 反序列化后回调 createTicketOrder：Redisson 锁 lock:order:{userId}
 *   防并发重复下单，条件更新 seckill_ticket.stock（stock>0）扣库存，成功才落 tb_ticket_order。
 * - 一致性：Lua 已保证 Redis 侧预检与扣减原子性，DB 侧库存/订单由消费端兜底，重试由 Kafka errorHandler 处理。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Slf4j
@Service
public class TicketOrderServiceImpl extends ServiceImpl<TicketOrderMapper, TicketOrder> implements ITicketOrderService {

    @Resource
    private ISeckillTicketService seckillTicketService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;

    /** 【订单管理的实现】门票表 Mapper（订单列表 join 票名/票价，mapper 注入避免经 Service 引发循环依赖） */
    @Resource
    private TicketMapper ticketMapper;

    /** 【订单支付的实现】用户表 Mapper（钱包余额校验与原子扣款，mapper 注入避免经 Service 引发循环依赖） */
    @Resource
    private UserMapper userMapper;

    /** 【积分抵扣的实现】积分流水表 Mapper（订单支付积分抵扣后落 tb_points_record 流水） */
    @Resource
    private PointsRecordMapper pointsRecordMapper;

    /** 【余额流水的实现】余额流水表 Mapper（订单支付扣款后落 tb_balance_record 消费流水） */
    @Resource
    private BalanceRecordMapper balanceRecordMapper;

    /** 【订单详情的实现】景点表 Mapper（订单详情 join 景点名，mapper 注入避免经 Service 引发循环依赖） */
    @Resource
    private AttractionMapper attractionMapper;

    /** 【订单管理的实现】订单状态 → 展示文案映射（1未支付 2已支付 3已核销 4已取消 5退款中 6已退款） */
    private static final Map<Integer, String> ORDER_STATUS_TEXT = new LinkedHashMap<>();

    static {
        ORDER_STATUS_TEXT.put(1, "未支付");
        ORDER_STATUS_TEXT.put(2, "已支付");
        ORDER_STATUS_TEXT.put(3, "已核销");
        ORDER_STATUS_TEXT.put(4, "已取消");
        ORDER_STATUS_TEXT.put(5, "退款中");
        ORDER_STATUS_TEXT.put(6, "已退款");
    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    /**
     * 【秒杀下单的实现】秒杀下单：Lua 预检 → 发 Kafka 异步落库 → 返回订单 id
     * <p>
     * 实现细节：
     * - 订单 id：RedisIdWorker.nextId("order") 基于 Redis 自增+时间戳生成全局唯一订单号，消费端直接落库。
     * - Lua 预检（seckill.lua，Redis 原子执行）：库存 key=seckill:stock:{ticketId} 不足返回 1（"库存不足"）；
     *   用户已下单（seckill:order:{ticketId} SISMEMBER）返回 2（"重复下单"）；通过则 INCRBY 扣库存、
     *   SADD 记录下单用户并返回 0。预检与扣减在同一脚本内原子完成，天然防超卖与一人多单。
     * - 异步落库：预检通过后构造 TicketOrder（id/userId/ticketId），发 Kafka topic=seckill-ticket-order，
     *   消息为 JSON 字符串（JSONUtil.toJsonStr，null 字段自动忽略），与消费端 StringDeserializer+JSONUtil.toBean 配套
     *   （详见内联【kafka消息队列的实现】注释）；DB 落库由 SeckillTicketConsumer → createTicketOrder 完成。
     * - 返回：请求立即返回订单 id（Result.ok(orderId)），秒杀结果异步最终一致。
     * @param ticketId 秒杀门票id
     * @return Result.data=订单id；库存不足/重复下单返回对应 fail
     */
    @Override
    public Result seckillTicket(Long ticketId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                ticketId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果
        if(result == 1){
            return Result.fail("库存不足");
        }
        if(result == 2){
            return Result.fail("重复下单");
        }
        // 有购买资格
        TicketOrder ticketOrder = new TicketOrder();
        ticketOrder.setId(orderId);    // 订单ID
        ticketOrder.setUserId(userId); // 用户ID
        ticketOrder.setTicketId(ticketId); // 门票ID

        // 3.指定Topic发送Kafka消息  【kafka消息队列的实现】
        // 实现细节：发送 JSON 字符串（JSONUtil.toJsonStr）而不是 toString()。
        // - 消费端（SeckillTicketConsumer）配置了 StringDeserializer + JSONUtil.toBean，
        //   二者必须配套：发送 JSON 字符串，消费端才能正确反序列化为 TicketOrder。
        // - 新建的 ticketOrder 只有 id/userId/ticketId 有值，其余字段为 null；
        //   JSONUtil 默认忽略 null 字段，输出形如 {"id":..,"userId":..,"ticketId":..}，
        //   避免 LocalDateTime 等空字段的序列化问题。
        // - 若用 ticketOrder.toString()（Lombok @Data 生成的字符串）发送，
        //   消费端 JSONUtil.toBean 无法解析，会造成消费链路失败。
        kafkaTemplate.send("seckill-ticket-order", JSONUtil.toJsonStr(ticketOrder));

        // 4.返回订单id
        return Result.ok(orderId);
    }

    /**
     * 【秒杀下单的实现】消费端落库：扣减库存 + 创建订单（Kafka 异步消费链路，由 SeckillTicketConsumer 调用）
     * <p>
     * 实现细节：
     * - 分布式锁：Redisson RLock lock:order:{userId} 保证同一用户同一时刻只有一个消费任务能走到落库，
     *   与 Lua 的 seckill:order 一人一单校验互补（防消费端并发重复扣库存/建单）；获取锁失败记 error 并直接返回。
     * - 扣库存：seckillTicketService.update() 条件更新 SET stock=stock-1 WHERE ticket_id=? AND stock>0，
     *   更新行数=0 说明库存已被扣完（返回 false 记 "库存不足！"），条件更新天然避免超卖。
     * - 建单：扣减成功才 save(ticketOrder) 落 tb_ticket_order，与扣库存在同一锁内串行执行；
     *   finally 中释放锁，异常时锁仍能释放，不会死锁。
     * @param ticketOrder 已由 seckill.lua 预检通过的订单（id/userId/ticketId 已填充，其余字段 DB 默认）
     */
    public void createTicketOrder(TicketOrder ticketOrder) {
        Long userId = ticketOrder.getUserId();
        Long ticketId = ticketOrder.getTicketId();
        // 【Redisson分布式锁的实现】创建锁对象：锁 key = lock:order:{userId}，按用户维度隔离——
        // 同一用户并发互斥、不同用户不互相阻塞。加锁原因：本方法是 Kafka 异步消费端（多线程），
        // 同一用户的订单消息可能因重试/乱序被并发消费，无锁会重复扣库存、重复建单；
        // Redisson 锁基于 Redis SETNX 实现，配合看门狗（watchdog）自动续期（默认续期 30s）防死锁，线程安全。
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 【锁获取的实现】非阻塞尝试获取锁：拿不到锁说明该用户已有消费任务正在执行（重复消费），直接拒绝；
        // tryLock() 无参 = 立即返回不等待，避免线程无限阻塞。
        boolean isLock = redisLock.tryLock();
        // 【锁获取失败分支】获取锁失败 = 同用户并发重复消费已在进行，记 error 并 return；
        // return 后消费端正常 ack（Kafka 不重投），重复消息的最终一致性靠 Redis seckill:order 一人一单保证；
        // 若存在业务性丢失场景，由 Kafka 重试机制或人工介入兜底。
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 【条件更新防超卖的实现】6.扣减库存：SET stock=stock-1 WHERE ticket_id=? AND stock>0——
            // 条件更新天然原子（MySQL 行锁），affected=0 说明库存已被扣完（超卖被挡）；
            // 与 Lua 预检的 Redis 库存形成双保险（Redis 挡大流量、DB 条件更新保证最终正确）。
            boolean success = seckillTicketService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("ticket_id", ticketId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足！");
                return;
            }
            // 【建单的实现】7.创建订单：扣减成功才 save(ticketOrder)，扣库存与建单在同一把锁内串行执行，
            // 保证"扣了必有单、有单必扣了"。
            save(ticketOrder);
        } finally {
            // 【锁释放的实现】finally 保证无论正常/异常都释放锁，不会死锁；
            // unlock 只释放自己持有的锁（Redisson 按线程/锁 id 校验），不会误释放他人锁。
            // 【Redis集群的实现】防御性释放：unlock 前先 isHeldByCurrentThread() 确认仍持有锁。
            // 原因：连接哨兵模式在 JVM 启动后首次使用 Redisson 锁时，可能与哨兵拓扑初始化产生竞态，
            // 偶发"锁实际未落在主节点"→ unlock 抛 IllegalMonitorStateException（attempt to unlock lock,
            // not locked by current thread）。若让该异常直接抛出，会掩盖上方建单结果并触发 Kafka 重试
            // （FixedBackOff×3），重试会重复执行 stock-1 造成库存漂移（已验证：一次瞬态 = 4 次重复扣减）。
            // 仅在确认持锁时释放，语义不变（只释放自己持有的锁），且异常不再外抛、不触发重试。
            if (redisLock.isHeldByCurrentThread()) {
                redisLock.unlock();
            }
        }
    }

    /**
     * 【订单超时的实现】取消超时未支付订单：条件更新状态 + DB 回补库存（由 OrderTimeoutTask 定时任务调用）
     * <p>
     * 实现细节：
     * - 条件更新：UPDATE tb_ticket_order SET status=4 WHERE id=? AND status=1——status 既做更新值又做
     *   过滤条件（1未支付→4已取消），保证同一超时订单只被处理一次：并发下第二个执行者条件更新
     *   affected=0 直接返回 false，幂等安全。
     * - DB 库存回增：UPDATE tb_seckill_ticket SET stock=stock+1 WHERE ticket_id=?——与状态更新同处
     *   @Transactional 事务，DB 侧"订单取消 + 库存恢复"原子提交。
     * - Redis 一致性：本方法只处理 DB 侧；Redis 的 seckill:stock:{ticketId} INCR（库存回增）与
     *   seckill:order:{ticketId} SREM（释放下单资格，用户可重新秒杀）由 OrderTimeoutTask 在事务提交后
     *   执行，保证 Redis 与 DB 秒杀口径最终一致（对应 seckill.lua 预检扣减的两处 Redis 写入）。
     * @param orderId  超时订单id
     * @param userId   下单用户id（仅供日志与 Redis 释放资格，Redis 操作在调用方执行）
     * @param ticketId 秒杀票id
     * @return true=本次成功取消并回补库存；false=条件更新未命中（已被其他流程处理），调用方跳过 Redis 操作
     */
    @Transactional
    @Override
    public boolean cancelExpiredOrder(Long orderId, Long userId, Long ticketId) {
        // 1.条件更新 status 1→4（WHERE status=1 保证只处理一次，affected=0 幂等跳过）
        boolean cancelled = update()
                .eq("id", orderId)
                .eq("status", 1)
                .set("status", 4)
                .update();
        if (!cancelled) {
            // 已被其他线程/流程处理，跳过（幂等）
            log.info("订单超时跳过处理（已被其他流程取消）: 订单id={}", orderId);
            return false;
        }
        // 2.DB 库存回增（与状态更新同一事务，原子提交）
        seckillTicketService.update()
                .setSql("stock = stock + 1")
                .eq("ticket_id", ticketId)
                .update();
        log.info("订单超时自动取消成功(DB): 订单id={}, 用户id={}, 票id={}, 已回补 tb_seckill_ticket.stock+1",
                orderId, userId, ticketId);
        return true;
    }

    /**
     * 【订单管理的实现】当前登录用户的秒杀订单列表（分页，订单管理页）
     * <p>
     * 实现细节：
     * - 需登录：由 TicketOrderController.queryOrderList 调用，userId 取 UserHolder（登录拦截器保证非空）。
     * - 分页：page() 查 tb_ticket_order WHERE user_id=当前用户 按 create_time 倒序，
     *   每页 SystemConstants.MAX_PAGE_SIZE 条（MyBatis-Plus 分页插件已配置）。
     * - join 门票：收集本页订单去重后的 ticketId，一次 selectBatchIds 批量查 tb_ticket，
     *   组装 Map<ticketId, Ticket> 逐条取值——避免 N+1 逐条查询；门票已删除（理论上不应发生）时
     *   ticketName/ticketPrice 返回 null，前端判空。
     * - 金额口径：ticketPrice 直接取 tb_ticket.pay_value（单位：分，DB 口径，如 1 分=0.01 元），
     *   与实体 Ticket.payValue 字段一致，不在此处做元分换算。
     * - 返回：data=List<Map{id, ticketId, ticketName, ticketPrice, status, statusText, payType,
     *   createTime, payTime}>，total=该用户订单总数（供前端分页）。
     * - 【订单ID序列化的实现】：订单 id / ticketId 为 17 位雪花 Long（如 900000000000010101），
     *   超过 JS Number 安全整数上限 2^53≈9007199254740991，若以数字 JSON 序列化，前端 res.json()
     *   解析后会丢失末尾精度（如 101→100），导致列表点击跳转错误订单。故在 Map 中统一用
     *   String.valueOf() 转为字符串再序列化（JSON 中带引号），前端拿到的就是原值字符串，URL 参数
     *   无精度问题。只改本处 Map 组装，不动全局 Jackson Long→String 配置。
     * - 【秒杀券状态过滤的实现】status 参数（Integer，缺省 null=查全部）：非空时按订单状态精确过滤
     *   （1未支付 2已支付 3已核销 4已取消 5退款中 6已退款）——前端秒杀券页用 status=2（未使用）和
     *   status=3（已核销）各调一次本接口分页拉取；QueryWrapper.eq(condition, "status", status) 条件拼接，
     *   status=null 时不加过滤条件。
     * @param current 页码，默认第1页
     * @param status  订单状态过滤（1未支付 2已支付 3已核销 4已取消 5退款中 6已退款；null=全部）
     * @return data=订单列表，total=订单总数
     */
    @Override
    public Result queryOrderList(Integer current, Integer status) {
        // 1.当前登录用户（拦截器已保证非空）
        Long userId = UserHolder.getUser().getId();
        // 2.按 create_time 倒序分页查该用户订单（status 非空时精确过滤）
        Page<TicketOrder> page = page(
                new Page<>(current, SystemConstants.MAX_PAGE_SIZE),
                new QueryWrapper<TicketOrder>()
                        .eq("user_id", userId)
                        .eq(status != null, "status", status)
                        .orderByDesc("create_time"));
        List<TicketOrder> records = page.getRecords();
        // 3.批量 join 门票：一次 selectBatchIds 取本页涉及的票名/票价
        Map<Long, Ticket> ticketMap = new LinkedHashMap<>();
        Set<Long> ticketIds = records.stream()
                .map(TicketOrder::getTicketId)
                .collect(Collectors.toSet());
        if (!ticketIds.isEmpty()) {
            for (Ticket ticket : ticketMapper.selectBatchIds(ticketIds)) {
                ticketMap.put(ticket.getId(), ticket);
            }
        }
        // 4.组装列表项（statusText 文案映射见 ORDER_STATUS_TEXT）
        List<Map<String, Object>> result = new ArrayList<>(records.size());
        for (TicketOrder order : records) {
            Ticket ticket = ticketMap.get(order.getTicketId());
            Map<String, Object> item = new LinkedHashMap<>();
            // 雪花 Long 转 String：防前端 JS Number 精度丢失（见方法注释【订单ID序列化的实现】）
            item.put("id", String.valueOf(order.getId()));
            item.put("ticketId", String.valueOf(order.getTicketId()));
            item.put("ticketName", ticket == null ? null : ticket.getTitle());
            item.put("ticketPrice", ticket == null ? null : ticket.getPayValue());
            item.put("status", order.getStatus());
            item.put("statusText", ORDER_STATUS_TEXT.get(order.getStatus()));
            item.put("payType", order.getPayType());
            item.put("createTime", order.getCreateTime());
            item.put("payTime", order.getPayTime());
            result.add(item);
        }
        // 5.返回（total=订单总数，供前端分页）
        return Result.ok(result, page.getTotal());
    }

    /**
     * 【订单详情的实现】当前登录用户的单笔秒杀订单详情（含门票/景点信息）
     * <p>
     * 实现细节：
     * - 需登录：由 TicketOrderController.queryOrderDetail 调用，userId 取 UserHolder（登录拦截器保证非空）。
     * - 归属校验：getOne 查 tb_ticket_order WHERE id=orderId AND user_id=当前用户——非本人订单查不到，
     *   统一 fail"订单不存在"，不区分"订单不存在/非本人"，避免泄露他人订单号是否存在。
     * - join 门票：ticketMapper.selectById 取 tb_ticket 的 title（票名）/sub_title（副标题）/pay_value
     *   （票价，单位分）/rules（使用规则）；门票已删除（理论不应发生）时相应字段返回 null，前端判空。
     * - join 景点（增强）：经 ticket.attractionId 再查 tb_attraction 取景点名 attractionName。
     * - 金额口径：ticketPrice 直接取 tb_ticket.pay_value（分，与订单列表口径一致，不做元分换算）。
     * - 返回：data=Map{id, ticketId, ticketName, ticketPrice, status, statusText, payType, createTime,
     *   payTime, useTime, refundTime, rules, attractionName}，statusText 映射见 ORDER_STATUS_TEXT。
     * - 【订单ID序列化的实现】：与订单列表一致，id / ticketId 为 17 位雪花 Long，超过 JS Number
     *   安全整数上限 2^53，序列化为字符串（String.valueOf）避免前端 res.json() 精度丢失（101→100）
     *   导致详情页取错订单；只改本处 Map 组装，不动全局 Jackson 配置。Map 中其他雪花 Long id
     *   （如 ticket 相关 id）若被加入，同样应转 String。
     * @param orderId 订单id
     * @return data=订单详情 Map；订单不存在/非本人订单返回 fail"订单不存在"
     */
    @Override
    public Result queryOrderDetail(Long orderId) {
        // 1.当前登录用户（拦截器已保证非空）
        Long userId = UserHolder.getUser().getId();
        // 2.归属校验：id + user_id 双条件查询，非本人订单查不到 → fail"订单不存在"
        TicketOrder order = getOne(new QueryWrapper<TicketOrder>()
                .eq("id", orderId)
                .eq("user_id", userId));
        if (order == null) {
            return Result.fail("订单不存在");
        }
        // 3.join 门票：取票名/副标题/票价（分）/使用规则
        Ticket ticket = ticketMapper.selectById(order.getTicketId());
        // 4.join 景点（增强）：经 ticket.attraction_id 取景点名
        String attractionName = null;
        if (ticket != null && ticket.getAttractionId() != null) {
            Attraction attraction = attractionMapper.selectById(ticket.getAttractionId());
            attractionName = attraction == null ? null : attraction.getName();
        }
        // 5.组装详情（statusText 文案映射见 ORDER_STATUS_TEXT）
        Map<String, Object> detail = new LinkedHashMap<>();
        // 雪花 Long 转 String：防前端 JS Number 精度丢失（见方法注释【订单ID序列化的实现】）
        detail.put("id", String.valueOf(order.getId()));
        detail.put("ticketId", String.valueOf(order.getTicketId()));
        detail.put("ticketName", ticket == null ? null : ticket.getTitle());
        detail.put("ticketPrice", ticket == null ? null : ticket.getPayValue());
        detail.put("status", order.getStatus());
        detail.put("statusText", ORDER_STATUS_TEXT.get(order.getStatus()));
        detail.put("payType", order.getPayType());
        detail.put("createTime", order.getCreateTime());
        detail.put("payTime", order.getPayTime());
        detail.put("useTime", order.getUseTime());
        detail.put("refundTime", order.getRefundTime());
        detail.put("rules", ticket == null ? null : ticket.getRules());
        detail.put("attractionName", attractionName);
        return Result.ok(detail);
    }

    /**
     * 【订单支付的实现】钱包余额支付未支付订单：余额校验 + 原子扣款 + 条件更新订单状态（全在一事务），
     * 支持积分抵扣（1 积分抵 0.01 元，任意正整数积分均可）
     * <p>
     * 实现细节（顺序即正确性关键）：
     * - ① 归属校验：getOne 查 tb_ticket_order WHERE id=orderId AND user_id=当前用户，查不到统一
     *   fail"订单不存在"（含他人订单，不泄露存在性）。
     * - ② 状态校验：仅 status=1（未支付）可支付；status=2 fail"订单已支付"、status=4（含被 OrderTimeoutTask
     *   超时取消的）fail"订单已取消，无法支付"、3/5/6 分别提示已核销/退款中/已退款——与超时任务的
     *   条件更新（status 1→4）配套，未支付状态被并发取消时能在此处正确拦截。
     * - ③ 金额换算：查 tb_ticket.pay_value（单位：分）→ BigDecimal.valueOf(payValue, 2) 换算元
     *   （如 5000 分=50.00 元），全程 BigDecimal 避免浮点误差。
     * - ③.5 积分抵扣（usePoints 处理）：
     *   - usePoints=null 按 0 处理（不抵扣，兼容旧调用不传 body/字段）；usePoints&lt;0 → fail"积分抵扣不能为负"；
     *   - 任意正整数均可抵扣（1 积分=0.01 元，不再限制 100 的整数倍）；
     *   - 积分充足校验：tb_user.points &lt; usePoints → fail"积分不足"（先校验，不扣任何钱）；
     *   - 抵扣金额 = usePoints/100（元，BigDecimal.divide(100, 2, HALF_UP) 保留 2 位小数）；
     *   - 应付金额 = 原价 - 抵扣金额；允许抵扣但不产生 0 元订单——抵扣后应付 < 0.01 时按 0.01 元实付
     *     （max(原价-抵扣, 0.01)），且积分仍按 usePoints 全额扣减。
     * - ④ 余额校验：查 tb_user.balance（元），balance < 应付 → fail"余额不足，请先充值"（提示当前余额）。
     * - ⑤ 扣积分（usePoints>0 时）：UPDATE tb_user SET points=points-usePoints WHERE id=我 AND
     *   points>=usePoints——条件更新防并发超扣，affected=0 说明积分已被并发扣至不足（此时尚未扣余额），
     *   fail"积分不足"无需回滚。
     * - ⑤.5 原子扣款：UPDATE tb_user SET balance=balance-应付 WHERE id=我 AND balance>=应付——条件更新
     *   防并发超扣，affected=0 说明余额已被并发扣至不足（此时积分已扣），抛 RuntimeException 靠
     *   @Transactional 回滚积分扣减。
     * - ⑥ 状态更新：UPDATE tb_ticket_order SET status=2, pay_time=NOW(), pay_type=1 WHERE id=? AND
     *   status=1——条件更新防与超时任务/重复支付竞态；affected=0 说明订单状态已变化（已被取消/已支付），
     *   此时余额与积分已扣，抛 RuntimeException 使 @Transactional 自动回滚，保证"扣款与订单状态"原子一致。
     * - ⑥.5 写流水（与扣款/扣积分/状态更新同一事务，任一失败整体回滚）：
     *   - 余额消费流水：INSERT tb_balance_record(user_id, change_amount=负应付, type='pay',
     *     pay_method='balance', description='购买秒杀券：{票名}')；
     *   - 积分抵扣流水（usePoints>0 时）：INSERT tb_points_record(change_points=-usePoints,
     *     type='order', description='订单支付积分抵扣')。
     * - ⑦ 成功返回 ok，data=Map{remainingBalance=剩余余额（元）, usedPoints=本次抵扣积分,
     *   deductAmount=本次抵扣金额（元）}。
     * @param orderId   订单id
     * @param usePoints 抵扣积分（1 积分=0.01 元，任意正整数；null/0 表示不抵扣，负数 fail"积分抵扣不能为负"）
     * @return 支付成功 ok（data=剩余余额、抵扣积分、抵扣金额）；余额不足/积分不足/订单状态异常返回对应 fail
     */
    @Override
    @Transactional
    public Result payOrder(Long orderId, Integer usePoints) {
        // ① 归属校验：id + user_id 双条件查询，非本人订单查不到 → fail"订单不存在"
        Long userId = UserHolder.getUser().getId();
        TicketOrder order = getOne(new QueryWrapper<TicketOrder>()
                .eq("id", orderId)
                .eq("user_id", userId));
        if (order == null) {
            return Result.fail("订单不存在");
        }
        // ② 状态校验：仅未支付(1)可支付，其余状态返回对应提示（含被超时任务取消的 status=4）
        if (order.getStatus() == null || order.getStatus() != 1) {
            switch (order.getStatus()) {
                case 2:
                    return Result.fail("订单已支付");
                case 4:
                    return Result.fail("订单已取消，无法支付");
                case 3:
                    return Result.fail("订单已核销，无法支付");
                case 5:
                    return Result.fail("订单退款中，无法支付");
                case 6:
                    return Result.fail("订单已退款，无法支付");
                default:
                    return Result.fail("订单状态异常，无法支付");
            }
        }
        // ③ 金额换算：tb_ticket.pay_value（分）→ 元（BigDecimal，避免浮点误差）
        Ticket ticket = ticketMapper.selectById(order.getTicketId());
        if (ticket == null || ticket.getPayValue() == null) {
            return Result.fail("门票不存在");
        }
        BigDecimal amount = BigDecimal.valueOf(ticket.getPayValue(), 2);
        // ③.5 积分抵扣：任意正整数积分均可（1 积分=0.01 元），计算抵扣金额与应付金额（≥0.01 元）
        int usedPoints = usePoints == null ? 0 : usePoints;
        if (usedPoints < 0) {
            return Result.fail("积分抵扣不能为负");
        }
        // ④ 余额/积分校验：查一次用户，先校验积分充足（不足 fail，不扣任何钱）再校验余额
        User user = userMapper.selectById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        int points = user.getPoints() == null ? 0 : user.getPoints();
        if (points < usedPoints) {
            return Result.fail("积分不足");
        }
        BigDecimal balance = user.getBalance() == null ? BigDecimal.ZERO : user.getBalance();
        BigDecimal payable = amount;
        BigDecimal deduct = BigDecimal.ZERO;
        if (usedPoints > 0) {
            // 抵扣金额 = usePoints/100（元，1 积分=0.01 元，保留 2 位小数）
            deduct = BigDecimal.valueOf(usedPoints)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            // 应付 = 原价 - 抵扣；允许抵扣但至少实付 0.01 元（max(原价-抵扣, 0.01)，避免 0 元订单，
            // 积分仍按 usePoints 全额扣减）
            payable = amount.subtract(deduct);
            if (payable.compareTo(new BigDecimal("0.01")) < 0) {
                payable = new BigDecimal("0.01");
            }
        }
        if (balance.compareTo(payable) < 0) {
            return Result.fail("余额不足，请先充值（当前余额：" + balance.toPlainString() + " 元）");
        }
        // ⑤ 扣积分：条件更新防并发超扣，affected=0 → 积分已被并发扣至不足（尚未扣余额，无需回滚）
        if (usedPoints > 0) {
            int pointsDeducted = userMapper.update(null, new UpdateWrapper<User>()
                    .setSql("points = points - " + usedPoints)
                    .eq("id", userId)
                    .ge("points", usedPoints));
            if (pointsDeducted == 0) {
                return Result.fail("积分不足");
            }
        }
        // ⑤.5 原子扣款：条件更新防并发超扣，affected=0 → 余额已被并发扣至不足（积分已扣，抛异常回滚）
        int deducted = userMapper.update(null, new UpdateWrapper<User>()
                .setSql("balance = balance - " + payable)
                .eq("id", userId)
                .ge("balance", payable));
        if (deducted == 0) {
            throw new RuntimeException("余额不足，订单支付回滚");
        }
        // ⑥ 条件更新订单状态 1→2（防与超时任务/重复支付竞态）；affected=0 时余额与积分已扣，抛异常触发事务回滚
        boolean updated = update()
                .eq("id", orderId)
                .eq("status", 1)
                .set("status", 2)
                .set("pay_type", 1)
                .set("pay_time", LocalDateTime.now())
                .update();
        if (!updated) {
            // 订单状态已变化（已被超时取消/重复支付），靠 @Transactional 抛异常自动回滚⑤/⑤.5 的扣积分与扣款
            throw new RuntimeException("订单状态已变化，请刷新");
        }
        // ⑥.5 写流水（pay=消费 + order=积分抵扣），与扣款/扣积分/状态更新同一事务
        // 积分抵扣落流水（order=下单抵扣，change_points=-usePoints）
        if (usedPoints > 0) {
            PointsRecord pointsRecord = new PointsRecord();
            pointsRecord.setUserId(userId);
            pointsRecord.setChangePoints(-usedPoints);
            pointsRecord.setType("order");
            pointsRecord.setDescription("订单支付积分抵扣");
            pointsRecordMapper.insert(pointsRecord);
        }
        // 余额消费流水（pay=消费，change_amount=负应付）
        BalanceRecord balanceRecord = new BalanceRecord();
        balanceRecord.setUserId(userId);
        balanceRecord.setChangeAmount(payable.negate());
        balanceRecord.setType("pay");
        balanceRecord.setPayMethod("balance");
        balanceRecord.setDescription("购买秒杀券：" + (ticket.getTitle() == null ? "" : ticket.getTitle()));
        balanceRecordMapper.insert(balanceRecord);
        log.info("订单余额支付成功: 订单id={}, 用户id={}, 应付={}元(原价{}元), 抵扣积分={}个/抵扣{}元, 剩余余额={}元",
                orderId, userId, payable.toPlainString(), amount.toPlainString(),
                usedPoints, deduct.toPlainString(), balance.subtract(payable).toPlainString());
        // ⑦ 返回剩余余额 + 本次抵扣积分 + 抵扣金额
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("remainingBalance", balance.subtract(payable));
        data.put("usedPoints", usedPoints);
        data.put("deductAmount", deduct);
        return Result.ok(data);
    }
}
