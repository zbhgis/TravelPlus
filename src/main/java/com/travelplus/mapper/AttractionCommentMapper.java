package com.travelplus.mapper;

import com.travelplus.entity.AttractionComment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 【景点评论数据访问的实现】tb_attraction_comment 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用
 * CRUD/分页能力；景点评论的发表/分页查询在 AttractionCommentServiceImpl 基于条件构造器实现。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface AttractionCommentMapper extends BaseMapper<AttractionComment> {

}
