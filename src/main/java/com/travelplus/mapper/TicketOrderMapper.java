package com.travelplus.mapper;

import com.travelplus.entity.TicketOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 【订单数据访问的实现】tb_ticket_order 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 秒杀下单落库（createTicketOrder）、订单查询等由 Service 层调用，一人一单查重基于 userId+ticketId 条件查询。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface TicketOrderMapper extends BaseMapper<TicketOrder> {

}
