package com.travelplus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>
 * 【旅行计划实体的实现】旅行计划表 tb_trip 实体，一条计划属于一个创建者（userId），
 * 记录标题、目的地城市与出行日期区间（startDate~endDate，create 时校验 end>=start）。
 * </p>
 * <p>
 * 实现细节（三表关联）：
 * - 计划 → 日程：tb_trip.id 与 tb_trip_day.trip_id 一对多；日程 → 景点安排：
 *   tb_trip_day.id 与 tb_trip_day_item.day_id 一对多。删除计划时按此链级联删
 *   items → days → trip（见 TripServiceImpl.delete）。
 * - startDate/endDate 为 LocalDate 日期类型，JSON 请求体按 yyyy-MM-dd 反序列化。
 * - createTime/updateTime 由数据库 DEFAULT CURRENT_TIMESTAMP / ON UPDATE 自动维护。
 * </p>
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_trip")
public class Trip implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键（创建成功后由 MyBatis-Plus 回填，作为 create 接口返回的计划 id）
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 创建者用户id（服务端从 UserHolder 填充，不信任前端；归属校验与删除权限以此为准）
     */
    private Long userId;

    /**
     * 计划标题（create 时强校验非空，≤100 字）
     */
    private String title;

    /**
     * 目的地城市（create 时强校验非空，≤50 字）
     */
    private String destination;

    /**
     * 开始日期
     */
    private LocalDate startDate;

    /**
     * 结束日期（create 时校验 endDate 不能早于 startDate）
     */
    private LocalDate endDate;

    /**
     * 创建时间（数据库 DEFAULT CURRENT_TIMESTAMP 自动生成）
     */
    private LocalDateTime createTime;

    /**
     * 更新时间（数据库 ON UPDATE CURRENT_TIMESTAMP 自动维护）
     */
    private LocalDateTime updateTime;

}
