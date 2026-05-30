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
 * 【收藏功能的实现】用户收藏表 tb_favorite 实体：区分收藏类型（note=游记 / attraction=景点）。
 * 唯一约束 uk_user_target(user_id, target_type, target_id) 保证同一用户对同一目标只收藏一次，
 * 重复收藏幂等返回成功（见 FavoriteServiceImpl.favorite 注释）；收藏是运行时业务数据，种子不写入。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_favorite")
public class Favorite implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 收藏者用户id
     */
    private Long userId;

    /**
     * 收藏目标类型：note=游记 / attraction=景点
     */
    private String targetType;

    /**
     * 收藏目标id（游记id或景点id）
     */
    private Long targetId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
