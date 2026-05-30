package com.travelplus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelplus.entity.TripDay;

/**
 * <p>
 * 【旅行日程数据访问的实现】tb_trip_day 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 按计划取全部日程（orderByAsc day_index）、按计划批量删除（级联删除中间层）在 Service 层
 * 基于条件构造器实现。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
public interface TripDayMapper extends BaseMapper<TripDay> {

}
