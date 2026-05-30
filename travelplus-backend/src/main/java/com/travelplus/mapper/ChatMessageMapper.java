package com.travelplus.mapper;

import com.travelplus.entity.ChatMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 【私信数据访问的实现】tb_chat_message 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 私信收发、会话聚合、未读统计与已读更新均由 Service 层基于 fromUserId/toUserId 操作。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {

}
