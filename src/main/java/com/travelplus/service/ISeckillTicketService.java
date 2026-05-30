package com.travelplus.service;

import com.travelplus.entity.SeckillTicket;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 * 【秒杀功能的实现】秒杀门票服务接口：承载 tb_seckill_ticket（与 tb_ticket 一对一），秒杀链路库存来源
 * </p>
 * <p>
 * 实现细节：
 * - 秒杀下单消费端（TicketOrderServiceImpl.createTicketOrder）通过本服务条件更新
 *   seckill_ticket.stock（SET stock=stock-1 WHERE stock>0）完成库存扣减。
 * - 秒杀券在新增门票时随 addSeckillTicket 一并写入；Redis 预热库存 key=seckill:stock:{ticketId}
 *   供 seckill.lua 预检使用。
 * </p>
 *
 * @author TravelPlus
 * @since 2022-01-04
 */
public interface ISeckillTicketService extends IService<SeckillTicket> {

}
