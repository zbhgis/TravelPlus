package com.travelplus.mapper;

import com.travelplus.entity.AttractionType;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 【景点类型数据访问的实现】tb_attraction_type 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 供分类导航列表（GET /attraction-type/list）按 sort 升序查询全部类型。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface AttractionTypeMapper extends BaseMapper<AttractionType> {

}
