package com.travelplus.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.Result;
import com.travelplus.entity.SeckillTicket;
import com.travelplus.entity.Ticket;
import com.travelplus.mapper.TicketMapper;
import com.travelplus.service.ISeckillTicketService;
import com.travelplus.service.ITicketService;
import com.travelplus.service.cache.TicketListCacheService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.List;

import static com.travelplus.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Service
public class TicketServiceImpl extends ServiceImpl<TicketMapper, Ticket> implements ITicketService {

    @Resource
    private ISeckillTicketService seckillTicketService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private TicketListCacheService ticketListCacheService;

    @Override
    public Result queryTicketOfAttraction(Long attractionId) {
        // 【秒杀门票信息多级缓存改造】查询门票信息（L1 Caffeine + L2 Redis）
        List<Ticket> tickets = ticketListCacheService.getTicketByAttractionId(attractionId);
        // 返回结果
        return Result.ok(tickets);
    }

    @Override
    @Transactional
    public void addSeckillTicket(Ticket ticket) {
        // 保存门票
        save(ticket);
        // 保存秒杀信息
        SeckillTicket seckillTicket = new SeckillTicket();
        seckillTicket.setTicketId(ticket.getId());
        seckillTicket.setStock(ticket.getStock());
        seckillTicket.setBeginTime(ticket.getBeginTime());
        seckillTicket.setEndTime(ticket.getEndTime());
        seckillTicketService.save(seckillTicket);
    }
}
