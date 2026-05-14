package com.travelplus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelplus.entity.Ticket;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 *  Mapper 接口
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface TicketMapper extends BaseMapper<Ticket> {

    // 查询秒杀门票,where type = 1
    List<Ticket> queryTicketOfAttraction(@Param("attractionId") Long attractionId);
}
