package com.travelplus.service;

import com.travelplus.dto.Result;
import com.travelplus.entity.TicketOrder;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface ITicketOrderService extends IService<TicketOrder> {

    Result seckillTicket(Long ticketId);

    void createTicketOrder(TicketOrder ticketOrder);
}
