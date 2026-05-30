package com.travelplus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelplus.entity.Favorite;

/**
 * <p>
 * 【收藏数据访问的实现】tb_favorite 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力。
 * 收藏写入/取消/列表/状态/计数均由 Service 层基于当前登录用户 + 目标维度操作。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
public interface FavoriteMapper extends BaseMapper<Favorite> {

}
