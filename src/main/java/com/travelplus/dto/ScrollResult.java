package com.travelplus.dto;

import lombok.Data;

import java.util.List;

/**
 * 【滚动分页响应体的实现】基于 ZSet 时间轴（如关注流 feed）的游标分页返回结构。
 * list 为当前页数据；minTime 为本次最后一条的 score（下次请求作为 lastId 游标）；
 * offset 为与 minTime 同分值的偏移量，避免同秒多条记录重复/遗漏。
 */
/**
 * 【滚动分页返回封装】关注流/时间线类接口的滚动游标分页响应
 * <p>
 * 实现细节：
 * - 与页码分页（Page）不同，滚动分页以"时间游标"推进：list=本次返回的数据；
 *   minTime=本次结果中最小的分数（时间戳）；offset=与 minTime 同分的条数。
 * - 前端下次请求带上 {lastId=minTime, offset} 即可接着往下翻，minTime/offset 组合能
 *   正确处理同一时间戳发布的多条数据（同分去重），避免重复或漏掉。
 */
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
