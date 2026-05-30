package com.travelplus.mapper;

import com.travelplus.entity.AttractionRating;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 【动态评分数据访问的实现】tb_attraction_rating 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用
 * CRUD/分页能力；打分 upsert（先查后插/改）与动态均分 AVG 聚合在 AttractionServiceImpl 基于条件构造器
 * selectObjs 实现（selectObjs 取首列聚合值，见 impl 注释）。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface AttractionRatingMapper extends BaseMapper<AttractionRating> {

}
