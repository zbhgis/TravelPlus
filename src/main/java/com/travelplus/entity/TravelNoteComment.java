package com.travelplus.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import java.time.LocalDateTime;
import java.io.Serializable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 【评论实体的实现】游记评论表 tb_travel_note_comment 实体。
 * parentId=0 为一级评论，>0 表示挂在某条一级评论下的回复；answerId 为被回复的评论 id。
 * icon/name 为 @TableField(exist=false) 的非表字段，查询时补充评论者信息（详细说明见字段注释）。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_travel_note_comment")
public class TravelNoteComment implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 【评论功能的实现】用户图标（非表字段，不落库）
     * <p>
     * 实现细节：
     * - @TableField(exist = false)：只用于响应传输，不映射 tb_travel_note_comment 表列，
     *   MyBatis-Plus 查询/插入时自动忽略（风格同 TravelNote.icon）。
     * - 由 queryComments 查询列表时经 IUserService.getById 补充，未取到时为 null。
     */
    @TableField(exist = false)
    private String icon;

    /**
     * 【评论功能的实现】用户昵称（非表字段，不落库）
     * <p>
     * 实现细节：
     * - @TableField(exist = false)：只用于响应传输，不映射 tb_travel_note_comment 表列，
     *   MyBatis-Plus 查询/插入时自动忽略（风格同 TravelNote.name）。
     * - 由 queryComments 查询列表时经 IUserService.getById 补充；用户不存在时兜底为"匿名"。
     */
    @TableField(exist = false)
    private String name;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * 游记id
     */
    private Long noteId;

    /**
     * 关联的1级评论id，如果是一级评论，则值为0
     */
    private Long parentId;

    /**
     * 回复的评论id
     */
    private Long answerId;

    /**
     * 回复的内容
     */
    private String content;

    /**
     * 点赞数
     */
    private Integer liked;

    /**
     * 【评论置顶的实现】置顶标志，1=置顶，0=不置顶
     * <p>
     * 实现细节：
     * - 对应 tb_travel_note_comment.is_top 表字段（TINYINT NOT NULL DEFAULT 0）。
     * - 仅一级评论（parent_id=0）可置顶，由游记作者通过 topComment 设置/取消；
     *   同一游记最多置顶 3 条，超出时取消最旧的置顶（见 TravelNoteCommentServiceImpl.topComment）。
     * - queryComments 返回时随记录透出，前端据此渲染"置顶"标识与排序。
     */
    private Integer isTop;

    /**
     * 状态，0：正常，1：被举报，2：禁止查看
     */
    private Boolean status;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
