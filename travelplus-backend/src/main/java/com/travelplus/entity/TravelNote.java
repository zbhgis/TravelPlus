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
import java.util.Map;

/**
 * <p>
 * 【游记实体的实现】游记表 tb_travel_note 实体，用户发布的图文游记（标题/多图/正文，可关联景点）。
 * icon/name/isLike 为 @TableField(exist=false) 的非表字段，查询时补充作者信息与当前用户点赞态。
 * 点赞数 liked 与 Redis 集合 note:liked:{noteId} 联动，点赞接口先写 Redis 再异步同步 DB。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_travel_note")
public class TravelNote implements Serializable {

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
     * 用户id
     */
    private Long userId;
    /**
     * 用户图标
     */
    @TableField(exist = false)
    private String icon;
    /**
     * 用户姓名
     */
    @TableField(exist = false)
    private String name;
    /**
     * 是否点赞过了
     */
    @TableField(exist = false)
    private Boolean isLike;

    /**
     * 是否收藏过了（登录用户查询时补充，未登录为 null）
     */
    @TableField(exist = false)
    private Boolean isFavorite;

    /**
     * 收藏数量（详情查询时实时统计）
     */
    @TableField(exist = false)
    private Integer favoriteCount;

    /**
     * 标题
     */
    private String title;

    /**
     * 游记的照片，最多9张，多张以","隔开
     */
    private String images;

    /**
     * 游记的文字描述
     */
    private String content;

    /**
     * 点赞数量
     */
    private Integer liked;

    /**
     * 评论数量
     */
    private Integer comments;

    /**
     * 【笔记可见性的实现】笔记可见性：0=公开（默认，所有人可见），1=仅自己可见。
     * 仅自己可见的笔记在热门列表/他人主页/关注 Feed/全局搜索中对非作者过滤，
     * 详情接口对非作者 fail"该笔记仅作者可见"；本人始终可见（含"我的笔记"列表）。
     */
    private Integer visible;

    /**
     * 【游记关联景点的实现】关联景点信息 {id,name,image}（@TableField(exist=false) 非表字段，
     * 游记详情查询时若 attractionId 非空则 join tb_attraction 补全，作为游记正文的景点入口；
     * 未关联景点保持 null，见 TravelNoteServiceImpl.attachAttractionInfo）
     */
    @TableField(exist = false)
    private Map<String, Object> attraction;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
