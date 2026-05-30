package com.travelplus.service;

import com.travelplus.dto.Result;

/**
 * 【全局搜索的实现】全局搜索业务接口：跨 景点/游记/用户 三类数据模糊检索。
 * <p>
 * 设计说明：
 * - 搜索范围：tb_attraction.name（景点）、tb_travel_note.title（游记）、tb_user.nick_name（用户），
 *   均使用 MyBatis-Plus Lambda/QueryWrapper 的 like 模糊匹配，不引入搜索引擎（LIKE 对 demo 数据量足够）。
 * - 对外能力：search 跨类检索、hotKeywords 热门词条推荐、suggest 输入联想，三者均为公开接口（无需登录）。
 * - 实现见 {@link com.travelplus.service.impl.SearchServiceImpl}。
 * </p>
 *
 * @author TravelPlus
 */
public interface ISearchService {

    /**
     * 【全局搜索的实现】跨三类数据全局搜索（公开）
     * <p>
     * 实现细节：
     * - keyword 非空时按 景点名/游记标题/用户昵称 分别 like 模糊查询，每类最多 10 条（SystemConstants.MAX_PAGE_SIZE）。
     * - keyword 为空直接返回三类空列表（不查库，避免全表扫描）。
     * - 返回结构：{attractions:[{id,name,image,score,avgPrice,destination}],
     *   notes:[{id,title,image,userId,name}], users:[{id,nickName,icon}]}。
     * </p>
     * @param keyword 搜索关键字（非空才检索）
     * @param current 页码（默认第1页，保留分页语义，简化实现为每类固定 MAX_PAGE_SIZE）
     * @return 三类搜索结果
     */
    Result search(String keyword, Integer current);

    /**
     * 【搜索词条推荐的实现】热门搜索词条推荐（公开）
     * <p>
     * 实现细节：
     * - 演示用固定常量数组返回 8~10 个热门词（杭州/西湖/断桥/灵隐寺/鼓浪屿/乌镇/千岛湖/厦门/龙井/雷峰塔），
     *   与平台景点/目的地强相关；不建搜索词表（不落库）。
     * - 后续可扩展为按搜索频次统计（如记录 keyword 到 Redis ZSet 计数取 Top N）后替换常量。
     * </p>
     * @return 热门搜索词列表（List&lt;String&gt;）
     */
    Result hotKeywords();

    /**
     * 【全局搜索的实现】输入联想（公开）
     * <p>
     * 实现细节：
     * - keyword 非空时 景点名/游记标题/用户昵称 三类分别 like 匹配取 top3（共 9 条），
     *   混合去重后最多返回 6 条（LinkedHashSet 保序去重 + 截断）。
     * - keyword 为空返回空列表。
     * - 返回扁平 List&lt;String&gt;（简单：前端输入框联想直接渲染字符串列表即可，
     *   无需按类型分组；若后续需按类型展示可改返回结构化对象）。
     * </p>
     * @param keyword 输入关键字（非空才联想）
     * @return 联想词列表（List&lt;String&gt;，最多 6 条）
     */
    Result suggest(String keyword);
}
