package com.travelplus.service.impl;

import com.travelplus.entity.SeckillTicket;
import com.travelplus.mapper.SeckillTicketMapper;
import com.travelplus.service.ISeckillTicketService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 【秒杀功能的实现】秒杀门票服务实现：承载 tb_seckill_ticket（与 tb_ticket 一对一）的 CRUD，秒杀库存数据源
 * <p>
 * 实现细节：
 * - 秒杀下单消费端（TicketOrderServiceImpl.createTicketOrder）经本服务条件更新库存
 *   （SET stock=stock-1 WHERE stock>0），保证不超卖。
 * - 秒杀券随 addSeckillTicket 写入；Redis seckill:stock:{ticketId} 预热库存供 seckill.lua 预检。
 *
 * @author TravelPlus
 * @since 2022-01-04
 */
@Service
public class SeckillTicketServiceImpl extends ServiceImpl<SeckillTicketMapper, SeckillTicket> implements ISeckillTicketService {

}
