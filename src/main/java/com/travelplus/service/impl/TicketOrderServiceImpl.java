package com.travelplus.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.Result;
import com.travelplus.entity.TicketOrder;
import com.travelplus.mapper.TicketOrderMapper;
import com.travelplus.service.ISeckillTicketService;
import com.travelplus.service.ITicketOrderService;
import com.travelplus.utils.RedisIdWorker;
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

import jakarta.annotation.Resource;
import java.util.Collections;

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
        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 6.扣减库存
            boolean success = seckillTicketService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("ticket_id", ticketId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足！");
                return;
            }
            // 7.创建订单
            save(ticketOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }
}
