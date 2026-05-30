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
 * 【动态评分实体的实现】景点评分表 tb_attraction_rating 实体，一个用户对某景点打一个 1~5 分的整数分
 * （score，TINYINT），唯一键 uk_ar(user_id, attraction_id) 保证"一人一票"——再次打分走 upsert（先查后改，
 * 用户可改分，见 AttractionServiceImpl.rate 注释）。景点动态均分 = AVG(该景点全部评分)，见
 * AttractionServiceImpl.queryById 注释。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_attraction_rating")
public class AttractionRating implements Serializable {

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
     * 打分用户id
     */
    private Long userId;

    /**
     * 评分 1~5
     */
    private Integer score;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
