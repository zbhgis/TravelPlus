package com.travelplus.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.Result;
import com.travelplus.entity.Attraction;
import com.travelplus.mapper.AttractionMapper;
import com.travelplus.service.IAttractionService;
import com.travelplus.utils.CacheClient;
import com.travelplus.utils.SystemConstants;
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
 * <p>
 *  服务实现类
 * </p>
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
        // 7.返回
        return Result.ok(attraction);
    }

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
