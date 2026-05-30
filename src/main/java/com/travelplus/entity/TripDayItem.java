package com.travelplus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;

/**
 * <p>
 * 【旅行景点安排实体的实现】旅行日程内景点安排表 tb_trip_day_item 实体，一条记录代表
 * 某一天行程里的一个景点安排，dayId 指向所属日程，attractionId 指向 tb_attraction 景点。
 * </p>
 * <p>
 * 实现细节（三表关联）：
 * - 计划 → 日程 → 景点安排 的最内层：day_id 有普通索引 idx_day 支撑按日程批量查询。
 * - note 为用户对该景点的备注（≤200 字，落库时空串处理，默认 ''）。
 * - sort 为当日内排序序号，由 TripServiceImpl.create 按 items 列表顺序从 0 递增生成，
 *   详情查询按 sort 升序返回。
 * - 景点信息（名称/首图）不在本表冗余存储，详情接口实时 JOIN tb_attraction 获取
 *   （景点已删除时返回"景点已下架"占位，见 TripServiceImpl.detail）。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_trip_day_item")
public class TripDayItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属日程id（tb_trip_day.id）
     */
    private Long dayId;

    /**
     * 景点id（tb_attraction.id，create 时校验必须存在）
     */
    private Long attractionId;

    /**
     * 备注（≤200 字，空串兜底）
     */
    private String note;

    /**
     * 当日内排序序号（从 0 递增）
     */
    private Integer sort;

}
