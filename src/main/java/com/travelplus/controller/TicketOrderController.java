package com.travelplus.controller;


import com.travelplus.dto.Result;
import com.travelplus.service.ITicketOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

import java.util.Map;

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

    /**
     * 【订单管理的实现】当前登录用户的秒杀订单列表（分页，订单管理页）
     * <p>
     * 实现细节：
     * - 需登录：GET /ticket-order/list 在 LoginInterceptor 拦截范围内（/ticket-order/** 不在 exclude
     *   列表），未登录返回 401；当前用户 id 由服务端从 UserHolder 获取，不信任前端传入。
     * - 业务全在 TicketOrderServiceImpl.queryOrderList（见其注释）：按 user_id=当前用户 倒序分页查
     *   tb_ticket_order，一次 listByIds/selectBatchIds 批量 join tb_ticket 取票名/票价；
     *   data=List<Map{id, ticketId, ticketName, ticketPrice, status, statusText, payType,
     *   createTime, payTime}>，total=订单总数。
     * - 【秒杀券状态过滤的实现】status 可选参数（缺省 null=查全部），非空时按订单状态精确过滤
     *   （1未支付 2已支付 3已核销 4已取消 5退款中 6已退款）——前端秒杀券页用 status=2（未使用）和
     *   status=3（已核销）各调一次本接口分页拉取。
     * @param current 页码，默认第1页
     * @param status  订单状态过滤（1未支付 2已支付 3已核销 4已取消 5退款中 6已退款；缺省=全部）
     * @return data=订单列表，total=订单总数
     */
    @GetMapping("/list")
    public Result queryOrderList(@RequestParam(value = "current", defaultValue = "1") Integer current,
                                 @RequestParam(value = "status", required = false) Integer status) {
        return ticketOrderService.queryOrderList(current, status);
    }

    /**
     * 【订单详情的实现】当前登录用户的单笔秒杀订单详情（含门票/景点信息）
     * <p>
     * 实现细节：
     * - 需登录：GET /ticket-order/{id} 被 LoginInterceptor 拦截（/ticket-order/** 不在 exclude 列表），未登录返回 401。
     * - 路由：@GetMapping("/{id}") 与已有的 /list 无冲突——Spring MVC 精确匹配优先，
     *   /ticket-order/list 命中 /list，/ticket-order/{id} 只承接其余单段路径。
     * - 归属校验在 service：TicketOrderServiceImpl.queryOrderDetail 按 id + user_id 双条件查询，
     *   非本人订单统一 fail"订单不存在"（见其注释）。
     * @param id 订单id
     * @return data=订单详情 Map{id, ticketId, ticketName, ticketPrice, status, statusText, payType,
     *   createTime, payTime, useTime, refundTime, rules, attractionName}
     */
    @GetMapping("/{id}")
    public Result queryOrderDetail(@PathVariable("id") Long id) {
        return ticketOrderService.queryOrderDetail(id);
    }

    /**
     * 【订单支付的实现】钱包余额支付未支付订单（余额不足拒绝支付，支持积分抵扣）
     * <p>
     * 实现细节：
     * - 需登录：POST /ticket-order/pay/{id} 被 LoginInterceptor 拦截（/ticket-order/** 不在 exclude
     *   列表），未登录返回 401；当前用户 id 由服务端从 UserHolder 获取，不信任前端传入。
     * - body 接收可选 {usePoints: 抵扣积分}（Map 解析风格同 /chat/send；缺省/null=0 不抵扣，
     *   保留无参兼容）——1 积分抵 0.01 元，积分可任意数量使用，抵扣后至少实付 0.01 元。
     * - 业务全在 TicketOrderServiceImpl.payOrder（见其注释）：归属校验 → 状态校验（仅 status=1 未支付可支付，
     *   与 OrderTimeoutTask 条件取消配套防竞态）→ 门票 pay_value（分）换算元 → 积分抵扣校验/计算应付 →
     *   扣积分+写积分流水 → 余额校验 → 原子扣款（UPDATE tb_user SET balance=balance-应付 WHERE id=我 AND balance>=应付）→
     *   条件更新订单 status 1→2（affected=0 抛异常事务回滚扣款）→ 写余额消费流水，
     *   全部在同一 @Transactional 内原子提交。
     * @param id   订单id
     * @param body 请求体（可选）{usePoints: 抵扣积分，1 积分抵 0.01 元，任意数量，缺省=0 不抵扣}
     * @return 支付成功 ok（data=剩余余额、抵扣积分、抵扣金额）；余额不足/积分不足/订单状态异常返回对应 fail
     */
    @PostMapping("pay/{id}")
    public Result payOrder(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        Integer usePoints = null;
        if (body != null) {
            Object p = body.get("usePoints");
            usePoints = p == null ? null : Integer.valueOf(String.valueOf(p));
        }
        return ticketOrderService.payOrder(id, usePoints);
    }
}
