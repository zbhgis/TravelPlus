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
 * 【门票实体的实现】门票表 tb_ticket 实体，归属于某个景点（attractionId）。
 * payValue/actualValue 金额单位分为「分」；status 控制上架状态。
 * stock/beginTime/endTime 为 @TableField(exist=false) 的非表字段，由秒杀券关联表 tb_seckill_ticket
 * 动态装配：三者非空即表示该门票为秒杀券（前端据此展示秒杀入口）。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_ticket")
public class Ticket implements Serializable {

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
     * 门票标题
     */
    private String title;

    /**
     * 副标题
     */
    private String subTitle;

    /**
     * 使用规则
     */
    private String rules;

    /**
     * 支付金额
     */
    private Long payValue;

    /**
     * 抵扣金额
     */
    private Long actualValue;

    /**
     * 门票类型
     */
    private Integer type;

    /**
     * 门票类型
     */
    private Integer status;
    /**
     * 库存
     */
    @TableField(exist = false)
    private Integer stock;

    /**
     * 生效时间
     */
    @TableField(exist = false)
    private LocalDateTime beginTime;

    /**
     * 失效时间
     */
    @TableField(exist = false)
    private LocalDateTime endTime;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;


    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
