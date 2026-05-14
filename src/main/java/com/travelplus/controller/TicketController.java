package com.travelplus.controller;


import com.travelplus.dto.Result;
import com.travelplus.entity.Ticket;
import com.travelplus.service.ITicketService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author TravelPlus
 */
@RestController
@RequestMapping("/ticket")
public class TicketController {

    @Resource
    private ITicketService ticketService;

    /**
     * 新增秒杀券
     * @param ticket 门票信息，包含秒杀信息
     * @return 门票id
     */
    @PostMapping("seckill")
    public Result addSeckillTicket(@RequestBody Ticket ticket) {
        ticketService.addSeckillTicket(ticket);
        return Result.ok(ticket.getId());
    }

    /**
     * 新增普通券
     * @param ticket 门票信息
     * @return 门票id
     */
    @PostMapping
    public Result addTicket(@RequestBody Ticket ticket) {
        ticketService.save(ticket);
        return Result.ok(ticket.getId());
    }


    /**
     * 查询景点的【秒杀】门票列表
     * @param attractionId 景点id
     * @return 门票列表
     */
    @GetMapping("/list/{attractionId}")
    public Result queryTicketOfAttraction(@PathVariable("attractionId") Long attractionId) {
       return ticketService.queryTicketOfAttraction(attractionId);
    }
}
