package com.travelplus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 【景点评论实体的实现】景点评论表 tb_attraction_comment 实体，一条评论挂在某个景点（attractionId）下，
 * 由某用户（userId）发布，content 为评论内容（≤500 字）。comment_count 不在表中维护，评论数由评论表
 * COUNT 实时统计（见 AttractionServiceImpl.queryById 注释）。
 * name/icon 为 @TableField(exist=false) 的非表字段，查询评论列表时补充评论作者昵称/头像（仿游记评论）。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_attraction_comment")
public class AttractionComment implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 景点id
     */
    private Long attractionId;

    /**
     * 评论用户id
     */
    private Long userId;

    /**
     * 评论内容
     */
    private String content;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 【景点评论的实现】评论作者昵称（非表字段，列表查询时补充）
     */
    @TableField(exist = false)
    private String name;

    /**
     * 【景点评论的实现】评论作者头像（非表字段，列表查询时补充）
     */
    @TableField(exist = false)
    private String icon;
}
