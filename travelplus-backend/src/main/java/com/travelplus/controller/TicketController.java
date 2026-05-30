package com.travelplus.controller;


import com.travelplus.dto.Result;
import com.travelplus.entity.Ticket;
import com.travelplus.service.ITicketService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * 【门票模块接口的实现】门票/秒杀券管理入口：新增普通券、新增秒杀券、查询景点门票列表
 * <p>
 * 实现细节：
 * - 路由域：@RequestMapping("/ticket")，挂 /ticket/** 前缀。
 * - 匿名可访问：/ticket/** 在 MvcConfig 登录拦截器 exclude 列表放行，无需 token。
 * - 秒杀相关字段（stock/beginTime/endTime）非空即视为秒杀券，见 queryTicketOfAttraction。
 *
 * @author TravelPlus
 */
@RestController
@RequestMapping("/ticket")
public class TicketController {

    @Resource
    private ITicketService ticketService;

    /**
     * 【新增秒杀券的实现】新增秒杀门票（门票 + 秒杀信息，同一事务落库）
     * <p>
     * 实现细节：
     * - body 接收 Ticket，除常规门票字段外须含 stock（秒杀库存）、beginTime/endTime（秒杀时段）。
     * - 委托 ticketService.addSeckillTicket：@Transactional 下先 save 门票，再保存 SeckillTicket
     *   （ticket_id/stock/beginTime/endTime），两步同事务提交，保证门票与秒杀信息不分离。
     * - 秒杀链路的 Redis 库存（seckill:stock:{ticketId}）由秒杀下单前的 Lua 预检读取，
     *   库存预热需与门票发布配套进行（见秒杀链路相关实现注释）。
     * @param ticket 门票信息，包含秒杀信息
     * @return 门票id
     */
    @PostMapping("seckill")
    public Result addSeckillTicket(@RequestBody Ticket ticket) {
        ticketService.addSeckillTicket(ticket);
        return Result.ok(ticket.getId());
    }

    /**
     * 【新增普通券的实现】新增普通门票
     * <p>
     * 实现细节：
     * - body 接收 Ticket，ticketService.save 直接落库，返回自增主键 id。
     * - 普通券不含秒杀时段/库存，查询列表时前端以 stock/beginTime/endTime 是否为空区分普通券与秒杀券。
     * @param ticket 门票信息
     * @return 门票id
     */
    @PostMapping
    public Result addTicket(@RequestBody Ticket ticket) {
        ticketService.save(ticket);
        return Result.ok(ticket.getId());
    }


    /**
     * 【门票列表查询的实现】查询某景点的门票列表（L1 Caffeine + L2 Redis 多级缓存）
     * <p>
     * 实现细节：
     * - 匿名可访问：/ticket/list/{attractionId} 在登录拦截器 exclude 列表放行，无需 token。
     * - 委托 ticketService.queryTicketOfAttraction：经 TicketListCacheService 多级缓存读取
     *   （L1 Caffeine + L2 Redis，缓存未命中查库并回填），data=List<Ticket>。
     * - stock/beginTime/endTime 非空的记录即为秒杀券，前端据此渲染"秒杀"角标与倒计时。
     * @param attractionId 景点id
     * @return 门票列表
     */
    @GetMapping("/list/{attractionId}")
    public Result queryTicketOfAttraction(@PathVariable("attractionId") Long attractionId) {
       return ticketService.queryTicketOfAttraction(attractionId);
    }
}
