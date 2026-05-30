package com.travelplus.service;

import com.travelplus.dto.Result;
import com.travelplus.entity.Ticket;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 【门票服务的实现】门票服务接口：查询景点门票（L1 Caffeine + L2 Redis 多级缓存）、新增门票并同步秒杀信息
 * <p>
 * 实现细节：
 * - 门票列表查询委托 TicketListCacheService 走多级缓存（caffeine→redis→DB，模式由 TicketCacheProperties 配置）。
 * - 新增门票在同一事务内写入 tb_ticket 与 tb_seckill_ticket（一对一关系），保证秒杀库存/时间一致。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface ITicketService extends IService<Ticket> {

    /**
     * 【秒杀门票信息多级缓存改造】查询某景点的门票列表
     * <p>
     * 实现细节：直接委托 TicketListCacheService.getTicketByAttractionId(attractionId)，
     * 按配置模式（mysql/redis/caffeine）决定缓存链路，默认 caffeine 本地缓存（refreshAfterWrite+expireAfterWrite
     * 双策略，load 时回源 Redis/DB）。
     * @param attractionId 景点id
     * @return 门票列表（含秒杀券的 stock/beginTime/endTime 字段）
     */
    Result queryTicketOfAttraction(Long attractionId);

    /**
     * 【门票新增的实现】新增门票并同步写入秒杀信息
     * <p>
     * 实现细节：@Transactional 同一事务内先 save(ticket) 落 tb_ticket，再构造 SeckillTicket
     * （stock/beginTime/endTime 取门票字段）经 seckillTicketService.save 落 tb_seckill_ticket，
     * 两表一对一关系保持一致。
     * @param ticket 待新增门票（stock/beginTime/endTime 非空即秒杀券）
     */
    void addSeckillTicket(Ticket ticket);
}
