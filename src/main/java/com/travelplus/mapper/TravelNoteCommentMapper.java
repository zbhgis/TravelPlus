package com.travelplus.mapper;

import com.travelplus.entity.TravelNoteComment;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 【评论数据访问的实现】tb_travel_note_comment 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 供游记评论列表（一级评论 + 回复）与评论发布功能使用。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface TravelNoteCommentMapper extends BaseMapper<TravelNoteComment> {

}
