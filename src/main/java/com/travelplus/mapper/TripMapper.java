package com.travelplus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelplus.entity.Trip;

/**
 * <p>
 * 【旅行计划数据访问的实现】tb_trip 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD/分页能力；
 * 计划的归属查询（当前用户分页）、详情与级联删除（先删 items/days）均在 Service 层基于
 * BaseMapper 条件构造器实现。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
public interface TripMapper extends BaseMapper<Trip> {

}
