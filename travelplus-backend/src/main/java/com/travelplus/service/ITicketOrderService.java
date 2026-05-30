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

    /**
     * 【订单超时的实现】取消超时未支付订单：条件更新状态 + DB 回补库存（由 OrderTimeoutTask 定时任务调用）
     * <p>
     * 实现细节：条件更新 UPDATE tb_ticket_order SET status=4 WHERE id=? AND status=1（status 同时作
     * 更新值与过滤条件，affected=0 说明已被其他流程处理，幂等跳过）；更新命中后同一 @Transactional
     * 事务内回补 tb_seckill_ticket.stock+1，DB 侧"订单取消+库存恢复"原子提交。Redis 侧库存回增（INCR
     * seckill:stock:{ticketId}）与下单集合移除（SREM seckill:order:{ticketId} {userId}，释放秒杀资格）
     * 由 OrderTimeoutTask 在事务提交后执行，保证与 seckill.lua 预检口径一致。
     * @param orderId  超时订单id
     * @param userId   下单用户id（用于日志与 Redis 释放资格）
     * @param ticketId 秒杀票id（用于回补库存）
     * @return true=本次成功取消并回补库存；false=条件更新未命中（已被处理），调用方跳过后续 Redis 操作
     */
    boolean cancelExpiredOrder(Long orderId, Long userId, Long ticketId);

    /**
     * 【订单管理的实现】当前登录用户的秒杀订单列表（分页，订单管理页）
     * <p>
     * 实现细节：查 tb_ticket_order WHERE user_id=当前登录用户 按 create_time 倒序分页
     * （每页 SystemConstants.MAX_PAGE_SIZE 条），一次 selectBatchIds 批量 join tb_ticket
     * 取票名/票价；statusText 映射（1未支付 2已支付 3已核销 4已取消 5退款中 6已退款），
     * 【秒杀券状态过滤的实现】status 非空时按订单状态精确过滤（前端秒杀券页 status=2 未使用、
     * status=3 已核销各调一次），详见 TicketOrderServiceImpl.queryOrderList 注释。
     * @param current 页码，默认第1页
     * @param status  订单状态过滤（1未支付 2已支付 3已核销 4已取消 5退款中 6已退款；null=全部）
     * @return data=List<Map{id, ticketId, ticketName, ticketPrice, status, statusText, payType, createTime, payTime}>，total=订单总数
     */
    Result queryOrderList(Integer current, Integer status);

    /**
     * 【订单详情的实现】当前登录用户的单笔秒杀订单详情（含门票信息）
     * <p>
     * 实现细节：归属校验（WHERE id=orderId AND user_id=当前用户，非本人订单统一 fail"订单不存在"，
     * 不泄露订单是否存在）；join tb_ticket 取票名/票价（分）/副标题/使用规则，join tb_attraction 取景点名。
     * data=Map{id, ticketId, ticketName, ticketPrice, status, statusText, payType, createTime,
     * payTime, useTime, refundTime, rules, attractionName}，详见 TicketOrderServiceImpl.queryOrderDetail 注释。
     * @param orderId 订单id
     * @return data=订单详情 Map；订单不存在/非本人订单返回 fail
     */
    Result queryOrderDetail(Long orderId);

    /**
     * 【订单支付的实现】钱包余额支付未支付订单（支持积分抵扣，余额不足拒绝支付）
     * <p>
     * 实现细节：状态校验（仅未支付 status=1 可支付，已支付/已取消等返回对应 fail）→ 门票 pay_value（分）
     * 换算元 → 【积分抵扣的实现】usePoints 任意正整数均可抵扣（1 积分=0.01 元，null=0 不抵扣，
     * 负数 fail"积分抵扣不能为负"），校验积分充足（不足 fail"积分不足"），抵扣金额=usePoints/100 元，
     * 应付=max(原价-抵扣, 0.01) 元 → 余额校验 → 扣积分（UPDATE tb_user SET points=points-usePoints
     * WHERE id=我 AND points>=usePoints，affected=0 fail"积分不足"）→ 原子扣款（UPDATE tb_user SET
     * balance=balance-应付 WHERE id=我 AND balance>=应付，affected=0 抛异常回滚积分扣减）→ 条件更新
     * 订单状态（UPDATE tb_ticket_order SET status=2, pay_time=NOW() WHERE id=? AND status=1，
     * affected=0 抛异常使事务回滚扣款）→ INSERT tb_balance_record(pay 消费流水) + INSERT
     * tb_points_record(order 抵扣流水)。全过程同一 @Transactional，扣款/扣积分/状态/流水原子提交，
     * 详见 TicketOrderServiceImpl.payOrder 注释。
     * @param orderId   订单id
     * @param usePoints 抵扣积分（1 积分=0.01 元，任意正整数；null/0 表示不抵扣，负数 fail"积分抵扣不能为负"）
     * @return 支付成功 ok（data=剩余余额、抵扣积分、抵扣金额）；余额不足/积分不足/订单状态异常返回对应 fail
     */
    Result payOrder(Long orderId, Integer usePoints);
}
