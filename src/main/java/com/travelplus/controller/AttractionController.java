package com.travelplus.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelplus.dto.Result;
import com.travelplus.entity.Attraction;
import com.travelplus.limiter.annotation.RateLimiter;
import com.travelplus.service.IAttractionService;
import com.travelplus.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * 【景点模块接口的实现】景点查询/管理统一入口：按 id 查详情、按类型分页（含滑动窗口限流）、按名称分页
 * <p>
 * 实现细节：
 * - 路由域：@RequestMapping("/attraction")，全部景点接口挂 /attraction/** 前缀。
 * - 匿名可访问：/attraction/** 在 MvcConfig 登录拦截器 exclude 列表放行，无需 token。
 * - 列表接口统一返回 Result：data=List<Attraction>，分页使用 MyBatis-Plus Page。
 * - queryAttractionByType 挂了 @RateLimiter 滑动窗口限流（5 秒窗口 5 次），超限抛 RateLimitException，
 *   由全局异常处理转换为 fail "查询次数过多，请稍后再试"。
 *
 * @author TravelPlus
 */
@RestController
@RequestMapping("/attraction")
public class AttractionController {

    @Resource
    public IAttractionService attractionService;

    /**
     * 【景点查询的实现】根据 id 查询景点详情
     * <p>
     * 实现细节：
     * - 匿名可访问：/attraction/{id} 已在登录拦截器 exclude 列表放行。
     * - 委托 attractionService.queryById(id)，返回 Attraction 实体：images 逗号分隔且以 /imgs/ 开头，
     *   score 为 ×10 的整数（前端 /10 展示），sold/comments 为热度数据。
     * @param id 景点id
     * @return 景点详情数据
     */
    @GetMapping("/{id}")
    public Result queryAttractionById(@PathVariable("id") Long id) {
        return attractionService.queryById(id);
    }

    /**
     * 【新增景点的实现】新增景点记录
     * <p>
     * 实现细节：
     * - body 接收完整 Attraction JSON，MyBatis-Plus save 落库后返回自增主键 id。
     * - 与 /attraction/** 放行策略一致，本接口未做登录限制，偏向后台/运营维护场景。
     * @param attraction 景点数据
     * @return 景点id
     */
    @PostMapping
    public Result saveAttraction(@RequestBody Attraction attraction) {
        // 写入数据库
        attractionService.save(attraction);
        // 返回景点id
        return Result.ok(attraction.getId());
    }

    /**
     * 【更新景点的实现】按 id 更新景点记录
     * <p>
     * 实现细节：
     * - body 接收 Attraction（须带 id），updateById 默认仅更新非 null 字段，
     *   未提交的字段保留原值；更新成功返回 ok。
     * @param attraction 景点数据（含 id）
     * @return 无
     */
    @PutMapping
    public Result updateAttraction(@RequestBody Attraction attraction) {
        // 写入数据库
        return attractionService.update(attraction);
    }

    /**
     * 【景点查询的实现】根据景点类型分页查询景点信息（含滑动窗口限流保护）
     * <p>
     * 实现细节：
     * - 限流注解 @RateLimiter(key="ticket:seckill:", window=5, limit=5, message="查询次数过多，请稍后再试",
     *   type=METHOD)：RateLimiterAspect 在方法执行前用 Redis 滑动窗口 Lua（limiter.lua）计数，
     *   5 秒窗口内超过 5 次即抛 RateLimitException → 全局异常处理返回 fail。限流维度为 METHOD
     *   （key 带类名+方法名，全方法共享额度，非按 IP/用户）；key 前缀沿用秒杀的旧配置，仅作示例。
     * - 匿名可访问：/attraction/of/type 在登录拦截器 exclude 列表放行，无需 token。
     * - 分页参数 current 默认第 1 页，每页 SystemConstants.MAX_PAGE_SIZE 条；x/y 为经纬度（预留，可选）。
     * - 委托 attractionService.queryAttractionByType 查询，data=当前页景点列表。
     * 测试： http://localhost:8081/attraction/of/type?typeId=1
     * @param typeId  景点类型（必填）
     * @param current 页码，默认第1页
     * @param x       经度（可选）
     * @param y       纬度（可选）
     * @return 景点列表
     */
    @RateLimiter(
            key = "ticket:seckill:",
            window = 5,
            limit = 5,
            message = "查询次数过多，请稍后再试",
            type = RateLimiter.LimitType.METHOD
    )
    @GetMapping("/of/type")
    public Result queryAttractionByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y) {
       return attractionService.queryAttractionByType(typeId, current, x, y);
    }

    /**
     * 【景点查询的实现】根据景点名称关键字分页查询景点信息
     * <p>
     * 实现细节：
     * - 匿名可访问：/attraction/of/name 在登录拦截器 exclude 列表放行。
     * - 名称模糊查询：name 非空时按 name LIKE 匹配（MyBatis-Plus lambdaQuery.like），
     *   name 为空则返回全量分页；current 默认第 1 页，每页 SystemConstants.MAX_PAGE_SIZE 条。
     * - 本接口未加 @RateLimiter（与 /of/type 区分：后者用于演示滑动窗口限流）。
     * @param name    景点名称关键字（可选，空则查全部）
     * @param current 页码，默认第1页
     * @return 景点列表
     */
    @GetMapping("/of/name")
    public Result queryAttractionByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Attraction> page = attractionService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
}
