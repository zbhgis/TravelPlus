package com.travelplus.controller;


import com.travelplus.dto.Result;
import com.travelplus.entity.Attraction;
import com.travelplus.limiter.annotation.RateLimiter;
import com.travelplus.service.IAttractionService;
import com.travelplus.service.cache.AttractionBloomFilterService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * 【景点模块接口的实现】景点查询/管理统一入口：按 id 查详情、按类型分页（含滑动窗口限流）、按名称分页、点赞/取消点赞
 * <p>
 * 实现细节：
 * - 路由域：@RequestMapping("/attraction")，全部景点接口挂 /attraction/** 前缀。
 * - 匿名可访问：/attraction/{id}、/attraction/of/type、/attraction/of/name、POST/PUT /attraction
 *   在 MvcConfig 登录拦截器 exclude 列表放行（原 /attraction/** 改为 /attraction/* + /attraction/of/**），
 *   无需 token；**PUT /attraction/like/{id} 不在放行列表**，被 LoginInterceptor 拦截需登录。
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

    /** 【布隆过滤器防穿透的实现】景点布隆过滤器服务（新增景点 save 成功后同步 add，保证后续查询不被第一道防线误拦） */
    @Resource
    private AttractionBloomFilterService bloomFilterService;

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
     * - save 回填 id 成功后调用 bloomFilterService.add(id) 同步布隆过滤器（【布隆过滤器防穿透的实现】），
     *   保证新景点详情查询不被第一道防线误拦。
     * @param attraction 景点数据
     * @return 景点id
     */
    @PostMapping
    public Result saveAttraction(@RequestBody Attraction attraction) {
        // 写入数据库
        attractionService.save(attraction);
        // 【布隆过滤器防穿透的实现】新增景点同步写布隆过滤器：save 回填自增 id 后 add，
        // 保证该新景点详情查询不被第一道防线误拦；布隆只增不删，故删除景点时无需 remove。
        if (attraction.getId() != null) {
            bloomFilterService.add(attraction.getId());
        }
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
     * 【景点点赞的实现】景点点赞/取消点赞切换（仿游记点赞，Redis ZSet，无 DB 计数）
     * <p>
     * 实现细节：
     * - 需登录：PUT /attraction/like/{id} 不在 MvcConfig 登录拦截器 exclude 放行列表
     *   （/attraction/** 已细化为 /attraction/* + /attraction/of/**，like 子路径被拦截），
     *   未登录返回 401；用户身份由服务端从 UserHolder 获取。
     * - 切换逻辑：Redis ZSet attraction:liked:{id}（ATTRACTION_LIKED_KEY）中是否含当前用户——
     *   不存在则 ZADD 用户（score=当前时间戳）；存在则 ZREM 移除（再次点击=取消），
     *   与游记点赞语义一致；景点无 liked 计数列，不做 DB 计数同步，赞过仅列表用途。
     * - 返回 ok；是否已点赞由详情接口 /attraction/{id} 的 isLike 字段回显（未登录为 null）。
     * @param id 景点id
     */
    @PutMapping("/like/{id}")
    public Result likeAttraction(@PathVariable("id") Long id) {
        return attractionService.likeAttraction(id);
    }

    /**
     * 【景点查询的实现】根据景点名称关键字分页查询景点信息
     * <p>
     * 实现细节：
     * - 匿名可访问：/attraction/of/name 在登录拦截器 exclude 列表放行。
     * - 名称模糊查询：委托 attractionService.queryAttractionByName 执行（name 非空按 name LIKE
     *   匹配，name 为空则全量分页；每页 SystemConstants.MAX_PAGE_SIZE 条），返回前 service 内
     *   attachListInfo 批量补充动态评分/打卡数/门票信息，主页卡片评分与详情一致。
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
        return attractionService.queryAttractionByName(name, current);
    }

    /**
     * 【动态评分的实现】用户给景点打分（1~5，唯一约束 upsert，用户可改分）
     * <p>
     * 实现细节：
     * - 请求体：{attractionId, score}（score 为 1~5 整数）。
     * - 需登录：POST /attraction/rate 与匿名路径 /attraction/* 匹配冲突（未被拦截器放行列表排除），
     *   因此登录校验在 service 内完成（UserHolder 判空 → fail"请先登录"），与 likeAttraction 双保险模式一致。
     * - 打分语义：先查后插/改（uk_ar(user_id,attraction_id) 唯一键一人一票，可改分），
     *   动态均分由详情查询 AVG 实时计算，见 service 注释。
     * @return 打分成功返回 ok
     */
    @PostMapping("/rate")
    public Result rateAttraction(@RequestBody java.util.Map<String, Object> body) {
        Long attractionId = body.get("attractionId") == null ? null : Long.valueOf(body.get("attractionId").toString());
        Integer score = body.get("score") == null ? null : Integer.valueOf(body.get("score").toString());
        return attractionService.rate(attractionId, score);
    }

    /**
     * 【动态评分的实现】查询某用户对某景点的打分（详情 myRating 的独立查询入口，可选接口）
     * <p>
     * 实现细节：
     * - GET /attraction/rate/status?attractionId=&userId=（路径两段，未被 /attraction/* 放行）
     *   被 LoginInterceptor 拦截需登录，未登录返回 401；userId 可选，缺省=当前登录用户。
     * - 返回 data=1~5 整数，未打分 data 为 null。
     * @param attractionId 景点id
     * @param userId       用户id（可选，缺省=当前登录用户）
     * @return data=打分 1~5；未打分为 null
     */
    @GetMapping("/rate/status")
    public Result rateStatus(@RequestParam("attractionId") Long attractionId,
                             @RequestParam(value = "userId", required = false) Long userId) {
        return attractionService.rateStatus(attractionId, userId);
    }

    /**
     * 【打卡与足迹的实现】用户打卡某景点（唯一约束幂等：已打卡返回 ok 不重复计数）
     * <p>
     * 实现细节：
     * - POST /attraction/checkin/{id}（路径两段，未被 /attraction/* 放行）被 LoginInterceptor 拦截
     *   需登录，未登录返回 401；service 仍 UserHolder 判空兜底。
     * - 打卡记录即足迹来源（/user/checkins/{id} 展示）；该景点打卡 COUNT=来过统计（详情 checkinCount）。
     * @param id 景点id
     * @return 打卡成功（或已打卡）返回 ok
     */
    @PostMapping("/checkin/{id}")
    public Result checkinAttraction(@PathVariable("id") Long id) {
        return attractionService.checkin(id);
    }

    /**
     * 【城市推荐景点的实现】去重目的地城市列表（公开，按景点数倒序）
     * <p>
     * 实现细节：匿名可访问——GET /attraction/destinations 与 /attraction/* 匹配（放行列表内），无需 token；
     * 按 tb_attraction.destination 分组统计景点数排序，data=List&lt;String&gt; 城市名（如 ["杭州","厦门"]）。
     * @return 城市列表
     */
    @GetMapping("/destinations")
    public Result queryDestinations() {
        return attractionService.queryDestinations();
    }

    /**
     * 【城市推荐景点的实现】按目的地城市分页查询景点列表（公开，按 destination 列等值筛选）
     * <p>
     * 实现细节：匿名可访问——GET /attraction/of/destination 与 /attraction/of/** 匹配（放行列表内），
     * 无需 token；city 必填，current 默认第 1 页，每页 MAX_PAGE_SIZE 条，data=List&lt;Attraction&gt;。
     * @param city    目的地城市（必填）
     * @param current 页码，默认第1页
     * @return 该城市景点列表
     */
    @GetMapping("/of/destination")
    public Result queryAttractionByDestination(
            @RequestParam("city") String city,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return attractionService.queryByDestination(city, current);
    }
}
