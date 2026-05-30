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
/**
 * 【秒杀门票信息多级缓存改造】门票列表多级缓存服务：L1 Caffeine 本地缓存 + L2 Redis 缓存 + DB 兜底
 * <p>
 * 实现细节：
 * - 缓存层级：L1=Caffeine LoadingCache（key=attractionId，value=门票列表 JSON），
 *   refreshAfterWrite 触发异步刷新（读旧值返回）、expireAfterWrite 硬过期兜底；
 *   L2=Redis key=cache:ticket:list:{attractionId} 存门票列表 JSON；DB 为最终兜底数据源。
 * - 加载链路（loadFromRedisThenDb）：L1 miss → 读 L2 Redis，命中直接返回；miss → 查 DB，
 *   DB 数据非空才写回 Redis（避免空数据缓存），返回给 L1。
 * - 模式开关：cacheProperties.getMode() 支持 mysql（直查 DB）/redis（L2+DB）/caffeine（默认，三级链路）。
 * - 缓存项缺失时由 CacheLoader.load 自动回源，Caffeine 保证同一 key 并发只回源一次。
 */
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
    /**
     * 【秒杀门票信息多级缓存改造】初始化 Caffeine 本地缓存
     * <p>
     * 实现细节：maximumSize(1000) 限制容量；refreshAfterWrite（到点后下次访问异步刷新，读旧值返回，
     * 提升数据一致性）与 expireAfterWrite（硬过期，过期后回源加载）两个时间均取
     * TicketCacheProperties 配置；CacheLoader.load 回源到 loadFromRedisThenDb（L2 Redis → DB）。
     */
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
    /**
     * 【秒杀门票信息多级缓存改造】按景点id获取门票列表（按配置模式选择缓存链路）
     * <p>
     * 实现细节：mode=mysql 直查 DB；mode=redis 走 L2 Redis+DB（loadFromRedisThenDb）；
     * 默认 caffeine 直接取本地缓存，miss 时由 load 函数回源并缓存。
     * @param attractionId 景点id
     * @return 该景点的门票列表（无则空列表）
     */
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
    /**
     * 【秒杀门票信息多级缓存改造】L2 Redis → DB 的加载链路（Caffeine load 回源入口）
     * <p>
     * 实现细节：先读 Redis key=cache:ticket:list:{attractionId}（JSON），命中反序列化返回；
     * miss 则查 DB（loadFromDb），DB 数据非空才写回 Redis（TTL 取配置 redisTtl），
     * 避免将空数据写入缓存造成缓存穿透。
     * @param attractionId 景点id
     * @return 门票列表（无则空列表）
     */
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

    /**
     * 【秒杀门票信息多级缓存改造】直接查询 DB 获取门票列表（最终兜底数据源）
     * <p>
     * 实现细节：ticketMapper.queryTicketOfAttraction(attractionId) 联表/条件查询；
     * 返回 null 时归一为空列表，避免上层空指针。
     * @param attractionId 景点id
     * @return 门票列表（无则空列表）
     */
    private List<Ticket> loadFromDb(Long attractionId) {
        List<Ticket> tickets = ticketMapper.queryTicketOfAttraction(attractionId);
        return tickets == null ? Collections.emptyList() : tickets;
    }
}
