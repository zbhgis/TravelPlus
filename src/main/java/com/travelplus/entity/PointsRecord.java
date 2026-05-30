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
 * 【积分记录实体的实现】积分流水表 tb_points_record 实体，记录用户积分的每一次增减。
 * changePoints 正=增加、负=扣减；type 取值：sign=每日签到奖励 / order=下单扣减 /
 * refund=退款返还 / admin=运营调整；description 为可读的变动描述。
 * 签到积分 +2 时由 UserServiceImpl.sign 写入，钱包页积分记录列表直接分页读本表。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_points_record")
public class PointsRecord implements Serializable {

    private static final long serialVersionUID = 1L;

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
     * 积分变动值，正=增加 负=扣减
     */
    private Integer changePoints;

    /**
     * 变动类型：sign=签到 / order=下单扣减 / refund=退款返还 / admin=运营调整
     */
    private String type;

    /**
     * 变动描述
     */
    private String description;

    /**
     * 变动时间
     */
    private LocalDateTime createTime;


}
