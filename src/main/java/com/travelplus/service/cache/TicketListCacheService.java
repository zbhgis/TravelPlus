package com.travelplus.service.cache;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.travelplus.config.TicketCacheProperties;
import com.travelplus.entity.Ticket;
import com.travelplus.mapper.TicketMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static com.travelplus.utils.RedisConstants.CACHE_TICKET_LIST_KEY;

@Slf4j
@Service
public class TicketListCacheService {

    @Resource
    private TicketMapper ticketMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private TicketCacheProperties cacheProperties;

    // 1. 【秒杀门票信息多级缓存改造】caffeine缓存的定义, key是attractionId, value是门票列表的json字符串
    private LoadingCache<Long, List<Ticket>> ticketListCache;

    // 2. 【秒杀门票信息多级缓存改造】caffeine缓存的初始化,会在项目启动时调用
    @PostConstruct
    public void initCache() {
        // 3. 【秒杀门票信息多级缓存改造】本地缓存的配置项(刷新时间、过期时间)、Redis缓存的过期时间
        /*
            refreshAfterWrite(x, TimeUnit.MILLISECONDS)
            含义：写入后多久触发“刷新”。
            到了这个时间后，下一次访问该 key 时，会触发重新加载最新值（注意是异步线程 refresh，当前线程会先返回旧值）。
            重点：它不是立刻删除缓存，而是让缓存“该更新了" 提高数据一致性

            expireAfterWrite(y, TimeUnit.MILLISECONDS)
            含义：写入后多久“过期失效”。
            超过这个时间，缓存项会被当作不存在，下次访问会走加载逻辑（通常同步加载新值）。
            重点：这是硬过期时间，过期后旧值不再可用。
        */
        ticketListCache = Caffeine.newBuilder()
                .maximumSize(1000)  // 缓存最大容量值
                .refreshAfterWrite(cacheProperties.getRefreshAfterWrite().toMillis(), TimeUnit.MILLISECONDS)
                .expireAfterWrite(cacheProperties.getExpireAfterWrite().toMillis(), TimeUnit.MILLISECONDS)
                .build(new CacheLoader<Long, List<Ticket>>() {
                    @Override
                    public List<Ticket> load(Long attractionId) {
                        return loadFromRedisThenDb(attractionId);
                    }
                });
    }


    // 通过景点ID获取景点的秒杀门票列表
    public List<Ticket> getTicketByAttractionId(Long attractionId) {
        String mode = cacheProperties.getMode();
        switch (mode.toLowerCase(Locale.ROOT)) {
            case "mysql":
                return loadFromDb(attractionId);
            case "redis":
                return loadFromRedisThenDb(attractionId);
            case "caffeine":
            default:
                // 直接从本地缓存中获取,若本地缓存中不存在会执行load函数,加载最新数据再返回
                return ticketListCache.get(attractionId);
        }
    }


    // 本地缓存的load逻辑
    private List<Ticket> loadFromRedisThenDb(Long attractionId) {
        String key = CACHE_TICKET_LIST_KEY + attractionId;

        // 1) L2: 读Redis
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toList(json, Ticket.class);
        }

        // 2) Redis中不存在，读 MySQL
        List<Ticket> tickets = loadFromDb(attractionId);

        if(CollectionUtil.isNotEmpty(tickets)) {
            // 3) MySQL中数据不为空才写回 Redis, 避免写入空数据
            long ttlMillis = cacheProperties.getRedisTtl().toMillis();
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(tickets), ttlMillis, TimeUnit.MILLISECONDS);
        }

        return tickets;
    }

    private List<Ticket> loadFromDb(Long attractionId) {
        List<Ticket> tickets = ticketMapper.queryTicketOfAttraction(attractionId);
        return tickets == null ? Collections.emptyList() : tickets;
    }
}
