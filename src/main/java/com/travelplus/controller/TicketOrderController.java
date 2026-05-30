package com.travelplus.controller;


import com.travelplus.dto.Result;
import com.travelplus.service.ITicketOrderService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 * 【订单模块接口的实现】门票订单入口：秒杀下单
 * <p>
 * 实现细节：
 * - 路由域：@RequestMapping("/ticket-order")，挂 /ticket-order/** 前缀。
 * - 需登录：/ticket-order/** 在 LoginInterceptor 拦截范围内（不在 exclude 列表），未登录返回 401；
 *   下单用户 id 由服务端从 UserHolder 获取，不信任前端传入。
 * - 秒杀链路：Redis Lua 预检（seckill.lua）→ 通过后发 Kafka 消息异步落库，见 seckillTicket 注释。
 *
 * @author TravelPlus
 */
@RestController
@RequestMapping("/ticket-order")
public class TicketOrderController {

    @Resource
    private ITicketOrderService ticketOrderService;

    /**
     * 【秒杀下单的实现】秒杀下单：Lua 预检通过后返回订单id，异步落库
     * <p>
     * 实现细节：
     * - 需登录：/ticket-order/seckill/{id} 被 LoginInterceptor 拦截，未登录返回 401。
     * - 请求链路：controller 只取 path 中的 ticketId 委托 service，业务全在
     *   TicketOrderServiceImpl.seckillTicket（见其注释）：
     *   ① 先通过 RedisIdWorker 生成全局唯一订单 id（订单号不与数据库自增耦合，便于异步写入）；
     *   ② 执行 Redis Lua 脚本 seckill.lua 原子预检：
     *      - 读 seckill:stock:{ticketId} 判断库存，不足返回 1 → fail "库存不足"；
     *      - 查 seckill:order:{ticketId} 集合判断是否已下单，重复返回 2 → fail "重复下单"（一人一单）；
     *      - 通过则 INCRBY 库存 -1 并 SADD 订单集合，返回 0——高并发下减库存/防重都在 Redis 原子完成。
     *   ③ 预检通过即向 Kafka topic=seckill-ticket-order 发送 JSON 消息（JSONUtil.toJsonStr，见
     *      【kafka消息队列的实现】注释），请求立刻返回 orderId；真正的扣库库存+落订单由
     *      SeckillTicketConsumer 异步消费（Redisson 锁 + stock>0 扣减 + save + 手动 ack）。
     * - 同步异步结合的原因：秒杀峰值瞬时量大，Lua 预检把"谁能买"在 Redis 内原子裁定并挡掉
     *   大部分无效请求，DB 只承接已通过预检的少量写，吞吐与一致性兼顾。
     * @param ticketId 秒杀门票id
     * @return data=订单id（Long）
     */
    @PostMapping("seckill/{id}")
    public Result seckillTicket(@PathVariable("id") Long ticketId) {
        return ticketOrderService.seckillTicket(ticketId);
    }
}
