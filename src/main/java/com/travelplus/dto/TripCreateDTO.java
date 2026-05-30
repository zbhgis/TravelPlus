package com.travelplus.dto;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * <p>
 * 【旅行计划创建请求的实现】POST /trip 请求体 DTO，结构：
 * {title, destination, startDate, endDate, days:[{title, items:[{attractionId, note}]}]}。
 * </p>
 * <p>
 * 实现细节：
 * - 顶层字段对应 tb_trip：title 标题 / destination 目的地 / startDate 开始日期 /
 *   endDate 结束日期（LocalDate，按 yyyy-MM-dd 反序列化）。
 * - days 为日程列表，对应 tb_trip_day + tb_trip_day_item 两层（见嵌套静态类），
 *   服务端按 days 顺序生成 dayIndex（1..N）与 items 的 sort（0..N），不信任前端序号。
 * - 天数上限 30（TripServiceImpl.MAX_TRIP_DAYS）；校验不通过由 Service 返回 fail。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@Data
public class TripCreateDTO {

    /**
     * 计划标题（非空，≤100 字）
     */
    private String title;

    /**
     * 目的地城市（非空，≤50 字）
     */
    private String destination;

    /**
     * 开始日期
     */
    private LocalDate startDate;

    /**
     * 结束日期（不能早于 startDate）
     */
    private LocalDate endDate;

    /**
     * 日程列表（1~30 天）
     */
    private List<TripDayDTO> days;

    /**
     * 【旅行日程请求的实现】单日日程：title 当日主题（可空，落库空串），
     * items 为当日景点安排列表（可为空，存在则逐个校验 attractionId）。
     */
    @Data
    public static class TripDayDTO {

        /**
         * 当日主题（可选）
         */
        private String title;

        /**
         * 当日景点安排列表
         */
        private List<TripItemDTO> items;
    }

    /**
     * 【旅行景点安排请求的实现】单条景点安排：attractionId 景点id（必填且必须存在于 tb_attraction），
     * note 备注（可选，≤200 字）。
     */
    @Data
    public static class TripItemDTO {

        /**
         * 景点id（create 时校验存在）
         */
        private Long attractionId;

        /**
         * 备注（可选）
         */
        private String note;
    }
}
