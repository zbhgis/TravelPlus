package com.travelplus.service;

import com.travelplus.dto.Result;
import com.travelplus.entity.TicketOrder;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 【秒杀功能的实现】秒杀订单服务接口：秒杀下单（Lua 预检 → Kafka 异步落库）与消费端订单落库
 * <p>
 * 实现细节：
 * - seckillTicket 为秒杀入口：Redis Lua（seckill:stock/seckill:order）预检库存与一人一单，
 *   通过后发 Kafka topic=seckill-ticket-order 异步落库，请求立即返回订单 id。
 * - createTicketOrder 为消费端（SeckillTicketConsumer）调用的落库方法：Redisson 分布式锁 + 条件扣库存 + 建订单。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface ITicketOrderService extends IService<TicketOrder> {

    /**
     * 【秒杀下单的实现】秒杀下单：Lua 预检 → 发 Kafka 异步落库 → 返回订单 id
     * <p>
     * 实现细节：RedisIdWorker 生成订单 id；执行 seckill.lua（返回 1=库存不足、2=重复下单、
     * 0=通过并已扣库存+记录下单用户）；通过后构造 TicketOrder 发 Kafka topic=seckill-ticket-order
     * （JSON 字符串，与消费端 StringDeserializer+JSONUtil.toBean 配套），立即返回订单 id。
     * @param ticketId 秒杀门票id
     * @return Result.data=订单id；库存不足/重复下单返回对应 fail
     */
    Result seckillTicket(Long ticketId);

    /**
     * 【秒杀下单的实现】消费端落库：扣减库存 + 创建订单（Kafka 异步消费链路）
     * <p>
     * 实现细节：Redisson 锁 lock:order:{userId} 防同一用户并发重复下单；持锁后
     * 条件更新 seckill_ticket 的 stock-1（WHERE stock>0，失败=库存不足）；
     * 扣减成功才 save(ticketOrder) 落 tb_ticket_order。
     * @param ticketOrder 已由 seckill.lua 预检通过的订单（id/userId/ticketId 已填充）
     */
    void createTicketOrder(TicketOrder ticketOrder);
}
