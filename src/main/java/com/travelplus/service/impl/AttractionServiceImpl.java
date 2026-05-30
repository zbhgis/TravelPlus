package com.travelplus.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.entity.Attraction;
import com.travelplus.mapper.AttractionMapper;
import com.travelplus.service.IAttractionService;
import com.travelplus.service.IBrowseRecordService;
import com.travelplus.utils.CacheClient;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.travelplus.utils.RedisConstants.*;

/**
 * 【景点服务的实现】景点服务实现：详情查询（缓存防穿透/防击穿 + 浏览记录）、更新（Cache Aside 删缓存）、按类型查询（Geo 距离排序）
 * <p>
 * 实现细节：
 * - queryById 走 CacheClient：当前启用 queryWithPassThrough（缓存空值防穿透），互斥锁与逻辑过期两套
 *   防击穿方案注释保留备选；查询成功后对登录用户写浏览记录（member=attraction:{id}）。
 * - update 先更新 DB 再删除 Redis 缓存（Cache Aside），保证下次查询拉到新数据。
 * - queryAttractionByType：x/y 为空走 DB 分页，非空走 Redis GEO（GEOSEARCH + BYRADIUS 5000m +
 *   WITHDISTANCE）按距离排序分页；入口 5 秒限 5 次由 Controller @RateLimiter 实现。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Service
public class AttractionServiceImpl extends ServiceImpl<AttractionMapper, Attraction> implements IAttractionService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private IBrowseRecordService browseRecordService;

    /**
     * 【景点详情缓存查询的实现】景点详情查询：缓存防穿透 + 登录用户浏览记录
     * <p>
     * 实现细节：
     * - 缓存方案：cacheClient.queryWithPassThrough(CACHE_ATTRACTION_KEY, id, ...) 回源 DB 后
     *   把空值也写入 Redis（TTL 较短）防缓存穿透，高并发下不直接打 DB；
     *   备选方案注释保留——queryWithMutex（互斥锁）与 queryWithLogicalExpire（逻辑过期）均为防缓存击穿，
     *   可按需切换，当前默认穿透方案。
     * - 结果兜底：缓存与 DB 均无该 id 时返回 null → fail "景点不存在！"。
     * - 浏览记录：查询成功（景点存在）后，仅对已登录用户（UserHolder 判空）调用
     *   browseRecordService.record(userId, "attraction", id)（ZSet member=attraction:{id}，
     *   score=毫秒时间戳），游客浏览不产生记录。
     * @param id 景点id
     * @return 景点详情；不存在 fail "景点不存在！"
     */
    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        Attraction attraction = cacheClient
                .queryWithPassThrough(CACHE_ATTRACTION_KEY, id, Attraction.class, this::getById, CACHE_ATTRACTION_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Attraction attraction = cacheClient
        //         .queryWithMutex(CACHE_ATTRACTION_KEY, id, Attraction.class, this::getById, CACHE_ATTRACTION_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Attraction attraction = cacheClient
        //         .queryWithLogicalExpire(CACHE_ATTRACTION_KEY, id, Attraction.class, this::getById, 20L, TimeUnit.SECONDS);

        if (attraction == null) {
            return Result.fail("景点不存在！");
        }
        // 7.记录浏览（仅登录用户；游客不记录）
        UserDTO user = UserHolder.getUser();
        if (user != null) {
            browseRecordService.record(user.getId(), "attraction", id);
        }
        // 8.返回
        return Result.ok(attraction);
    }

    /**
     * 【景点更新的实现】更新景点并删除缓存（Cache Aside）
     * <p>
     * 实现细节：id 为空直接 fail；@Transactional 先 updateById 落库，成功后
     * stringRedisTemplate.delete(CACHE_ATTRACTION_KEY + id) 删除对应缓存，
     * 下次 queryById 重新回源 DB 并重建缓存，保证读到最新数据（先删缓存而非更新缓存，避免双写不一致）。
     * @param attraction 待更新景点（id 必填）
     * @return 更新成功返回 ok；id 为空 fail
     */
    @Override
    @Transactional
    public Result update(Attraction attraction) {
        Long id = attraction.getId();
        if (id == null) {
            return Result.fail("景点id不能为空");
        }
        // 1.更新数据库
        updateById(attraction);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_ATTRACTION_KEY + id);
        return Result.ok();
    }

    /**
     * 【景点按类型查询的实现】按景点类型分页查询，带坐标时按距离排序（Redis GEO）
     * <p>
     * 实现细节：
     * - 无坐标（x/y 任一为 null）：DB 分页查询，Page + eq(type_id) + DEFAULT_PAGE_SIZE，返回当前页记录。
     * - 带坐标：先算分页边界（from=(current-1)*页大小, end=current*页大小），再对 Redis GEO
     *   key=attraction:geo:{typeId} 执行 GEOSEARCH BYLONLAT (x,y) BYRADIUS 5000m WITHDISTANCE，
     *   一次取到 end 条（含距离），若结果不足 from 说明无下一页返回空列表；skip(from) 截取本页，
     *   得到 景点id 集合 + 距离映射；IN 查 DB + ORDER BY FIELD(id,...) 保持距离顺序，回填 distance 字段。
     * - 限流：接口入口由 Controller 层 @RateLimiter(window=5s, limit=5) 滑动窗口限流，
     *   超限 fail "查询次数过多，请稍后再试"，防止恶意刷取（本方法不含限流逻辑）。
     * @param typeId  景点类型id
     * @param current 页码（从1开始）
     * @param x       经度（可选）
     * @param y       纬度（可选）
     * @return 景点列表（带坐标时含 distance）；无数据返回空列表
     */
    @Override
    public Result queryAttractionByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Attraction> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：attractionId、distance
        String key = ATTRACTION_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取景点id
            String attractionIdStr = result.getContent().getName();
            ids.add(Long.valueOf(attractionIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(attractionIdStr, distance);
        });
        // 5.根据id查询Attraction
        String idStr = StrUtil.join(",", ids);
        List<Attraction> attractions = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Attraction attraction : attractions) {
            attraction.setDistance(distanceMap.get(attraction.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(attractions);
    }
}
