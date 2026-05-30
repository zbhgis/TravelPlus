package com.travelplus.mapper;

import com.travelplus.entity.Follow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 【关注关系数据访问的实现】tb_follow 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 支撑关注/取关写入、是否关注查询、共同关注（与当前用户关注列表求交集）等关注链路。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface FollowMapper extends BaseMapper<Follow> {

}
