package com.travelplus.controller;

import com.travelplus.dto.Result;
import com.travelplus.dto.TripCreateDTO;
import com.travelplus.service.ITripService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * 【旅行计划接口的实现】旅行计划模块统一入口：创建 / 当前用户列表 / 详情 / 删除
 * <p>
 * 实现细节：
 * - 路由域：@RequestMapping("/trip")，全部旅行计划接口挂 /trip/** 前缀。
 * - 全部需登录：/trip/** 不在 MvcConfig LoginInterceptor exclude 放行列表，
 *   未登录访问返回 HTTP 401；用户身份由服务端从 UserHolder 获取（不信任前端传参）。
 * - 返回结构：统一 Result。创建成功 data=计划 id；列表 data=List<Map{id,title,destination,
 *   startDate,endDate,createTime}> + total；详情 data=三级结构 Map{trip..., days:[{id,dayIndex,
 *   title,items:[{id,attractionId,attractionName,attractionImage,note,sort}]}]}；删除成功 data 为空。
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@RestController
@RequestMapping("/trip")
public class TripController {

    @Resource
    private ITripService tripService;

    /**
     * 【旅行计划的实现】创建旅行计划
     * <p>
     * 实现细节：
     * - body 结构 {title,destination,startDate,endDate,days:[{title,items:[{attractionId,note}]}]}
     *   （startDate/endDate 为 yyyy-MM-dd）。
     * - 需登录：/trip POST 被 LoginInterceptor 拦截，未登录 401。
     * - 校验与三表落库见 TripServiceImpl.create；返回 data=新计划 id。
     * @param dto 请求体
     * @return data=计划id
     */
    @PostMapping
    public Result createTrip(@RequestBody TripCreateDTO dto) {
        return tripService.create(dto);
    }

    /**
     * 【旅行计划的实现】当前登录用户的计划分页列表（按创建时间倒序）
     * <p>
     * 实现细节：委托 tripService.list(current)；每条 {id,title,destination,startDate,
     * endDate,createTime}，days/items 由详情接口承载。每页 MAX_PAGE_SIZE(10) 条。
     * 测试：GET /trip/list?current=1（需登录，authorization 头带 token）
     * @param current 页码，默认第1页
     * @return data=当前页计划列表，total=总数
     */
    @GetMapping("/list")
    public Result queryTripList(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return tripService.list(current);
    }

    /**
     * 【旅行计划的实现】计划详情（三级结构 + 景点 JOIN）
     * <p>
     * 实现细节：委托 tripService.detail(id)；归属校验（仅创建者本人可查看，他人 fail 无权查看该计划）；
     * 返回 {id,title,destination,startDate,endDate,createTime,days:[{id,dayIndex,title,
     * items:[{id,attractionId,attractionName,attractionImage,note,sort}]}]}。
     * @param id 计划id
     * @return data=计划详情
     */
    @GetMapping("/{id}")
    public Result queryTripDetail(@PathVariable("id") Long id) {
        return tripService.detail(id);
    }

    /**
     * 【旅行计划的实现】删除计划（仅创建者可删，级联删 days/items）
     * <p>
     * 实现细节：委托 tripService.delete(id)；归属校验（非创建者 fail 无权删除该计划），
     * 级联删除顺序 items → days → trip（见 Service 实现注释）。
     * @param id 计划id
     * @return 删除成功返回 ok
     */
    @DeleteMapping("/{id}")
    public Result deleteTrip(@PathVariable("id") Long id) {
        return tripService.delete(id);
    }
}
