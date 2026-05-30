package com.travelplus.mapper;

import com.travelplus.entity.TravelNote;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 【游记数据访问的实现】tb_travel_note 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 支撑游记发布、热门榜单（按点赞量倒序）、按用户/按关注流查询等游记链路。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface TravelNoteMapper extends BaseMapper<TravelNote> {

}
