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
 * 【门票服务的实现】门票服务实现：门票列表走多级缓存查询，新增门票时同步写秒杀信息
 * <p>
 * 实现细节：
 * - queryTicketOfAttraction 委托 TicketListCacheService（L1 Caffeine + L2 Redis + DB 兜底，
 *   模式由配置切换），库存/起止时间由 Redis 预热 + seckill.lua 预检支撑秒杀链路。
 * - addSeckillTicket 在同一事务内落 tb_ticket 与 tb_seckill_ticket，保证一对一时间/库存一致。
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

    /**
     * 【秒杀门票信息多级缓存改造】查询某景点的门票列表（L1 Caffeine + L2 Redis 多级缓存）
     * <p>
     * 实现细节：直接委托 ticketListCacheService.getTicketByAttractionId(attractionId)——
     * 默认模式走 Caffeine（refreshAfterWrite 异步刷新 + expireAfterWrite 硬过期），miss 时
     * load 回源 Redis（cache:ticket:list:{attractionId}）再回源 DB，DB 非空才回写 Redis；
     * 可按配置切换 mysql/redis/caffeine 模式。返回的门票若 stock/beginTime/endTime 非空即秒杀券。
     * @param attractionId 景点id
     * @return 该景点的门票列表（无则空列表）
     */
    @Override
    public Result queryTicketOfAttraction(Long attractionId) {
        // 【秒杀门票信息多级缓存改造】查询门票信息（L1 Caffeine + L2 Redis）
        List<Ticket> tickets = ticketListCacheService.getTicketByAttractionId(attractionId);
        // 返回结果
        return Result.ok(tickets);
    }

    /**
     * 【门票新增的实现】新增门票并同步写入秒杀信息（同一事务）
     * <p>
     * 实现细节：@Transactional 保证两步原子——① save(ticket) 落 tb_ticket（含秒杀券的 stock/beginTime/endTime）；
     * ② 构造 SeckillTicket（ticketId 取门票主键，stock/beginTime/endTime 原样复制）经 seckillTicketService.save
     * 落 tb_seckill_ticket。任一步失败整体回滚，避免"有门票无秒杀库存"的不一致状态。
     * @param ticket 待新增门票（id 由 MyBatis-Plus 回填）
     */
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
