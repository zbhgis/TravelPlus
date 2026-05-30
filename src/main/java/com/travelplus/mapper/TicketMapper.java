package com.travelplus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelplus.entity.Ticket;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 【门票数据访问的实现】tb_ticket 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 自定义 queryTicketOfAttraction 关联秒杀券表，为景点门票列表（含秒杀标识）提供 SQL（见 TicketMapper.xml）。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface TicketMapper extends BaseMapper<Ticket> {

    /**
     * 查询指定景点下的秒杀门票（type=1 且 status=1 上架中），左连 tb_seckill_ticket 带出库存与起止时间
     * @param attractionId 景点 id
     * @return 门票列表，秒杀字段（stock/beginTime/endTime）可能为 null（无秒杀配置）
     */
    // 查询秒杀门票,where type = 1
    List<Ticket> queryTicketOfAttraction(@Param("attractionId") Long attractionId);
}
