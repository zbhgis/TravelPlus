package com.travelplus.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.travelplus.dto.UserDTO;
import java.time.LocalDateTime;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 【通知实体的实现】用户通知表 tb_notify 实体（小红书式：赞/关注/评论/回复/@ 消息）。
 * 通知是旁路数据：写入失败不阻塞点赞/关注/评论主流程（见 NotifyServiceImpl.add 的 try-catch 静默策略）。
 * userId=接收者、fromUserId=触发者、type=like/follow/comment/reply/mention、
 * targetId=目标（游记 id 或用户 id）、content=纯动作文案、isRead=0未读/1已读。
 * fromUser 为 @TableField(exist=false) 的非表字段，列表查询时补充触发者信息（id/nickName/icon）。
 * displayText 同为 @TableField(exist=false) 非表字段，展示层实时拼装（昵称+动作文案，不落库）。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_notify")
public class Notify implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 【通知系统的实现】触发者用户信息（非表字段，不落库）
     * <p>
     * 实现细节：
     * - @TableField(exist = false)：只用于响应传输，不映射 tb_notify 表列，
     *   MyBatis-Plus 查询/插入时自动忽略（风格同 TravelNote.icon/name）。
     * - 由 queryNotifyList 查询列表时经 IUserService.getById(fromUserId) 组装
     *   UserDTO{id, nickName, icon} 写入，前端消息列表渲染触发者头像昵称。
     */
    @TableField(exist = false)
    private UserDTO fromUser;

    /**
     * 【通知实时昵称的实现】展示文案（非表字段，不落库）：实时昵称 + " " + 纯动作 content
     * <p>
     * 实现细节：
     * - @TableField(exist = false)：只用于响应传输，不映射 tb_notify 表列（同 fromUser），
     *   MyBatis-Plus 查询/插入时自动忽略。
     * - content 只存纯动作文案（如"赞了你的游记《…》"，不含昵称前缀），昵称前缀由
     *   queryNotifyList 查询时用实时 fromUser.nickName 拼装写入本字段——用户改昵称后
     *   历史通知自动显示新昵称（小红书方案：昵称不落库、展示层实时拼装），前端渲染优先取 displayText。
     */
    @TableField(exist = false)
    private String displayText;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 接收者用户id
     */
    private Long userId;

    /**
     * 触发者用户id
     */
    private Long fromUserId;

    /**
     * 通知类型：like=点赞 / follow=关注 / comment=评论 / reply=回复 / mention=@提到
     */
    private String type;

    /**
     * 目标id：type=like/comment/reply/mention 时为游记id，type=follow 时为被关注用户id
     */
    private Long targetId;

    /**
     * 【通知实时昵称的实现】纯动作通知文案（如"赞了你的游记《…》"），不含昵称前缀；
     * 昵称由展示层 queryNotifyList 用实时 fromUser.nickName 拼装进 displayText 返回（小红书方案）
     */
    private String content;

    /**
     * 是否已读，0=未读，1=已读
     */
    private Integer isRead;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;


}
