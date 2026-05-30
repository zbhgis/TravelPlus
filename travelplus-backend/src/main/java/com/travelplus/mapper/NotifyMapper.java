package com.travelplus.mapper;

import com.travelplus.entity.Notify;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 【通知数据访问的实现】tb_notify 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 通知写入/列表分页/未读数统计/已读更新均由 Service 层基于接收者 userId 操作。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
public interface NotifyMapper extends BaseMapper<Notify> {

}
