package com.travelplus.service;

import com.travelplus.dto.Result;
import com.travelplus.entity.Attraction;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 【景点服务的实现】景点服务接口：详情查询（缓存防穿透/防击穿）、更新（更新后删缓存）、按类型分页查询（Geo 距离排序）
 * <p>
 * 实现细节：
 * - 详情查询走 CacheClient 缓存（当前启用 queryWithPassThrough 穿透方案，互斥锁/逻辑过期方案保留备选），
 *   登录用户浏览时同步写浏览记录（见 impl 注释）。
 * - 按类型查询支持"无坐标=DB 分页、带坐标=Redis GEO 按距离排序分页"两种模式，入口限流见 Controller。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface IAttractionService extends IService<Attraction> {

    /**
     * 【景点详情缓存查询的实现】查询景点详情：缓存防穿透 + 登录用户浏览记录
     * <p>
     * 实现细节：cacheClient.queryWithPassThrough 缓存空值防穿透（互斥锁/逻辑过期方案注释保留备选），
     * 查询成功后对登录用户写浏览记录（member=attraction:{id}，ZSet score=时间戳）。
     * @param id 景点id
     * @return 景点详情；不存在 fail "景点不存在！"
     */
    Result queryById(Long id);

    /**
     * 【景点更新的实现】更新景点后删除缓存
     * <p>
     * 实现细节：先 updateById 落库（@Transactional），再删除 Redis key=cache:attraction:{id}，
     * 保证下次查询重新加载新数据（Cache Aside 模式）。
     * @param attraction 待更新的景点（id 必填）
     * @return 更新成功返回 ok；id 为空 fail
     */
    Result update(Attraction attraction);

    /**
     * 【景点按类型查询的实现】按景点类型分页查询，支持按坐标距离排序
     * <p>
     * 实现细节：x/y 任一为空走 DB 分页（Page + DEFAULT_PAGE_SIZE）；均非空时走 Redis GEO
     * （GEOSEARCH BYLONLAT 半径 5000m + WITHDISTANCE）按距离升序取 end 条再截取 from~end，
     * 最后 IN 查 DB + ORDER BY FIELD 保持距离顺序并回填 distance 字段；入口限流由
     * Controller 层 @RateLimiter 注解实现（5 秒窗口限 5 次，超限 fail "查询次数过多"）。
     * @param typeId  景点类型id
     * @param current 页码（从1开始）
     * @param x       经度（可选，与 y 一起提供时走 GEO 距离排序）
     * @param y       纬度（可选）
     * @return 景点列表；无数据返回空列表
     */
    Result queryAttractionByType(Integer typeId, Integer current, Double x, Double y);
}
