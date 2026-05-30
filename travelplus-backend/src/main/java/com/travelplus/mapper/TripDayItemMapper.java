package com.travelplus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelplus.entity.TripDayItem;

/**
 * <p>
 * 【旅行景点安排数据访问的实现】tb_trip_day_item 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 按日程取景点安排（orderByAsc sort）、按 day_id 批量删除（级联删除最内层）在 Service 层
 * 基于条件构造器实现。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
public interface TripDayItemMapper extends BaseMapper<TripDayItem> {

}
