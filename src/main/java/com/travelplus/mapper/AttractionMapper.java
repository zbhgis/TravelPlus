package com.travelplus.mapper;

import com.travelplus.entity.Attraction;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 【景点数据访问的实现】tb_attraction 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD/分页能力；
 * 景点检索（按类型/按名称/按距离附近）在 Service 层基于 BaseMapper 条件构造器与 GEO 检索实现。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface AttractionMapper extends BaseMapper<Attraction> {

}
