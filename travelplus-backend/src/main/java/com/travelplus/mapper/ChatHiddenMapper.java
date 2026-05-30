package com.travelplus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelplus.entity.ChatHidden;

/**
 * <p>
 * 【私信会话隐藏的实现】tb_chat_hidden 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 会话隐藏（INSERT）、隐藏列表查询、自动解除（DELETE）均由 Service 层基于 userId/peerId 操作。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-08
 */
public interface ChatHiddenMapper extends BaseMapper<ChatHidden> {

}
