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
 * 【旅行日程实体的实现】旅行日程表 tb_trip_day 实体，一条计划下的"第几天"日程，
 * tripId 指向所属计划，dayIndex 为该天序号（1..N），title 为当日主题。
 * </p>
 * <p>
 * 实现细节（三表关联）：
 * - trip_id + day_index 上有唯一键 uk_trip_day，保证同一计划内天数序号不重复。
 * - 一个日程下挂多条景点安排（tb_trip_day_item.day_id 指向本表 id，见 TripDayItem）。
 * - dayIndex 由 TripServiceImpl.create 按 days 列表顺序从 1 递增生成，不信任前端传入值。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_trip_day")
public class TripDay implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键（插入后由 MyBatis-Plus 回填，作为景点安排的 day_id）
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 所属计划id（tb_trip.id）
     */
    private Long tripId;

    /**
     * 第几天，1..N（同一计划内唯一）
     */
    private Integer dayIndex;

    /**
     * 当日主题（可为空，落库时按空串处理，默认 ''）
     */
    private String title;

}
