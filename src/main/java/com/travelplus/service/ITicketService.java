package com.travelplus.service;

import com.travelplus.dto.Result;
import com.travelplus.entity.Ticket;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface ITicketService extends IService<Ticket> {

    Result queryTicketOfAttraction(Long attractionId);

    void addSeckillTicket(Ticket ticket);
}
