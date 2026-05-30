package com.travelplus.controller;

import com.travelplus.dto.Result;
import com.travelplus.service.ISearchIndexService;
import com.travelplus.service.ISearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 * 【全局搜索的实现】全局搜索接口（公开，无需登录）：跨 景点/游记/用户 三类数据模糊检索。
 * <p>
 * 实现细节：
 * - GET /search          —— 跨三类全局搜索（keyword like 匹配，每类最多 10 条），
 *   返回 {attractions:[{id,name,image,score,avgPrice,destination}],
 *         notes:[{id,title,image,userId,name}],
 *         users:[{id,nickName,icon}]}；keyword 为空返回三类空列表。
 * - GET /search/hot      —— 热门搜索词条（演示用固定 10 个词，不建表，见 SearchServiceImpl.HOT_KEYWORDS）。
 * - GET /search/suggest  —— 输入联想（景点名/游记标题/用户昵称 各 top3 混合去重，最多 6 条，扁平 List&lt;String&gt;）。
 * - 匿名放行：/search/** 已加入 MvcConfig 的 LoginInterceptor excludePathPatterns（搜索无需登录态）。
 * - 业务逻辑全部委托 {@link ISearchService}，本控制器仅做参数透传。
 * </p>
 *
 * @author TravelPlus
 */
@RestController
@RequestMapping("/search")
public class SearchController {

    @Resource
    private ISearchService searchService;
    @Resource
    private ISearchIndexService searchIndexService;

    /**
     * 【全局搜索的实现】跨三类全局搜索（公开接口）
     * <p>
     * 实现细节：keyword 非空时分别对 tb_attraction.name / tb_travel_note.title / tb_user.nick_name
     * 做 like 模糊查询，每类最多 SystemConstants.MAX_PAGE_SIZE(10) 条；结果封装为
     * {attractions, notes, users} 三类结构返回。详见 SearchServiceImpl.search。
     * </p>
     * @param keyword 搜索关键字（非空才检索）
     * @param current 页码（默认第1页，保留分页语义）
     * @return 三类搜索结果
     */
    @GetMapping
    public Result search(
            @RequestParam("keyword") String keyword,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        return searchService.search(keyword, current);
    }

    /**
     * 【搜索词条推荐的实现】热门搜索词条（公开接口）
     * <p>
     * 实现细节：返回 SearchServiceImpl.HOT_KEYWORDS 固定常量数组（10 个与平台景点/目的地相关的
     * 演示词条，不建搜索词表）；后续可扩展为按搜索频次统计。
     * </p>
     * @return 热门搜索词列表（List&lt;String&gt;）
     */
    @GetMapping("/hot")
    public Result hotKeywords() {
        return searchService.hotKeywords();
    }

    /**
     * 【全局搜索的实现】输入联想（公开接口）
     * <p>
     * 实现细节：keyword 非空时 景点名/游记标题/用户昵称 各取 top3 匹配项（like），
     * 混合去重后最多返回 6 条扁平 List&lt;String&gt;（前端输入框联想直接渲染，简单约定）。
     * </p>
     * @param keyword 输入关键字（非空才联想）
     * @return 联想词列表（最多 6 条）
     */
    @GetMapping("/suggest")
    public Result suggest(@RequestParam("keyword") String keyword) {
        return searchService.suggest(keyword);
    }

    /**
     * 【Elasticsearch搜索的实现】手动触发 ES 全量索引重建（公开接口，便于测试/运维）。
     * <p>
     * 实现细节：POST /search/index/rebuild → 委托 ISearchIndexService.fullRebuild()，先删旧索引
     * 再分页批量同步 MySQL 22 万级数据到 attraction_es/note_es/user_es，返回 {attraction,note,user} 同步计数；
     * 演示环境公开可调（/search/** 本就匿名放行），生产建议加权限/运维侧调用。
     * </p>
     * @return 各类索引同步条数
     */
    @PostMapping("/index/rebuild")
    public Result rebuildIndex() {
        return Result.ok(searchIndexService.fullRebuild());
    }
}
