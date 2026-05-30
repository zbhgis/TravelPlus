package com.travelplus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelplus.entity.ChatPin;

/**
 * <p>
 * 【会话置顶的实现】tb_chat_pin 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 会话置顶（INSERT）、置顶列表查询、取消置顶（DELETE）均由 Service 层基于 userId/peerId 操作。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-11
 */
public interface ChatPinMapper extends BaseMapper<ChatPin> {

}
