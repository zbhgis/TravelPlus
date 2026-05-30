package com.travelplus.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 【私信实体的实现】用户私信消息表 tb_chat_message 实体，fromUserId 发送者 → toUserId 接收者，
 * 支撑与任意用户的私信收发、会话列表聚合与未读标记。
 * </p>
 * <p>
 * 实现细节：
 * - fromUserId/toUserId 双向组合形成"我与对方"的一对一会话；content 最长 500 字（发送时强校验）。
 * - isRead：0=未读 1=已读，会话列表打开时把"对方发给我的未读"批量置读（见 ChatServiceImpl.list）。
 * - createTime 由数据库 DEFAULT CURRENT_TIMESTAMP 自动生成，发信时不显式赋值。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_chat_message")
public class ChatMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键（发送成功后由 MyBatis-Plus 回填，作为 send 接口返回的消息 id）
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 发送者用户id
     */
    private Long fromUserId;

    /**
     * 接收者用户id
     */
    private Long toUserId;

    /**
     * 消息内容（≤500 字）
     */
    private String content;

    /**
     * 是否已读，0=未读，1=已读
     */
    private Integer isRead;

    /**
     * 【私信会话隐藏的实现】已废弃（Deprecated）：接收方已删除标记，0=正常 1=已删除
     * <p>
     * 实现细节：
     * - 本字段来自上一版"单方删除"方案（deleteSession 时置 1 隐藏我的视角，并物理删我发的消息），
     *   历史反馈：恢复会话后历史不完整。
     * - 现改为"会话隐藏"方案：删除会话=写 tb_chat_hidden 隐藏表，消息永不删除、历史完整，
     *   代码（list/sessions/unreadCount/deleteSession）不再读写本字段与 is_deleted_by_me 列。
     * - 列保留（兼容存量数据，便于回滚），字段保留仅为数据库列映射，业务逻辑不引用。
     * </p>
     */
    private Integer isDeletedByMe;

    /**
     * 创建时间（数据库 DEFAULT CURRENT_TIMESTAMP 自动生成）
     */
    private LocalDateTime createTime;

}
