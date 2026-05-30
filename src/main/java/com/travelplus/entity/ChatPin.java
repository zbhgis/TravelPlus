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
 * 【会话置顶的实现】用户私信会话置顶表 tb_chat_pin 实体，user_id 置顶者 → peer_id 被置顶会话对象。
 * 支撑"会话置顶"方案：置顶会话只在此表记一条置顶关系，不碰消息与隐藏表；
 * 我的 sessions 列表通过本表把置顶会话排到最前（先置顶组后普通组，组内各自按 lastTime 倒序）。
 * </p>
 * <p>
 * 实现细节：
 * - userId/peerId 组合表示"置顶者视角"下该会话固定在列表前排；uk_pin(user_id, peer_id)
 *   唯一约束保证重复置顶幂等（INSERT 冲突即已置顶，忽略即可）。
 * - createTime 由数据库 DEFAULT CURRENT_TIMESTAMP 自动生成，作为置顶记录的时间戳。
 * - 取消置顶=删除本表对应记录（user_id=我 AND peer_id=peerId），会话回到按 lastTime 排序的普通位置。
 * - 与微信/小红书"会话置顶"语义一致：只影响我的视图排序，消息与对方视角完全不受影响。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-11
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_chat_pin")
public class ChatPin implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 置顶者用户id（=我的视角，置顶会话时写入）
     */
    private Long userId;

    /**
     * 被置顶会话对象用户id（对方）
     */
    private Long peerId;

    /**
     * 置顶时间（数据库 DEFAULT CURRENT_TIMESTAMP 自动生成）
     */
    private LocalDateTime createTime;

}
