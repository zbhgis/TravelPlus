package com.travelplus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 【私信会话隐藏的实现】用户私信会话隐藏表 tb_chat_hidden 实体，user_id 隐藏者 → peer_id 被隐藏会话对象。
 * 支撑"删除会话=隐藏视图"方案：删除会话不再删消息、不再标记，只在此表记一条隐藏关系；
 * 我的 sessions 列表通过本表排除被隐藏的 peer，对方新发消息时自动解除（删除本表记录）会话恢复。
 * </p>
 * <p>
 * 实现细节：
 * - userId/peerId 组合表示"隐藏者视角"下不显示与对方的一对一会话；uk_hide(user_id, peer_id)
 *   唯一约束保证重复隐藏幂等（INSERT 冲突即已隐藏，忽略即可）。
 * - createTime 由数据库 DEFAULT CURRENT_TIMESTAMP 自动生成，作为"隐藏时间"参照：
 *   对方在隐藏时间之后发给我的新消息（create_time &gt; 本表 create_time）触发自动解除。
 * - 与微信/小红书"删除会话"语义一致：消息永不删除、对方视角完全不受影响、完整历史永久保留。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-08
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_chat_hidden")
public class ChatHidden implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 隐藏者用户id（=我的视角，删除会话时写入）
     */
    private Long userId;

    /**
     * 被隐藏会话对象用户id（对方）
     */
    private Long peerId;

    /**
     * 隐藏时间（数据库 DEFAULT CURRENT_TIMESTAMP 自动生成，作为自动恢复的参照时间）
     */
    private LocalDateTime createTime;

}
