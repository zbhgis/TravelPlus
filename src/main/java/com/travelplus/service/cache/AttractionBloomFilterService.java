package com.travelplus.service.cache;

import com.travelplus.mapper.AttractionMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.List;

import static com.travelplus.utils.RedisConstants.BLOOM_ATTRACTION_KEY;

/**
 * 【布隆过滤器防穿透的实现】景点布隆过滤器服务：详情查询的第一道防线
 * <p>
 * 实现细节：
 * - 三道防线：① 布隆过滤器（本服务，确定不存在直接拦截，不查缓存/DB）→
 *   ② Redis 缓存（queryWithPassThrough 缓存命中）→ ③ DB + 空值占位（缓存未命中回源 DB，无数据写 2min 空值）。
 * - 为何作为第一道防线：布隆过滤器只存每个 id 的若干哈希位，26 条景点仅占 ~13 Byte/条 级内存，
 *   contains 为 O(1) 位图判断，不碰 Redis/DB——恶意批量不存在的 id（如 /attraction/999999、/attraction/1000000…）
 *   会被直接挡下，避免其穿透到空值占位路径写出大量 cache:attraction:{不存在id}="" 的垃圾 key 撑爆 Redis 内存。
 * - 只增不删：布隆过滤器无法删除元素（删一个位可能误删共享该位的其他元素），因此 add 只发生在
 *   景点新增（saveAttraction）与启动预热；误判只会把"不存在的 id"判成"可能存在"（安全侧），
 *   不会把"存在的 id"判成"不存在"（危险侧），拦截逻辑因此永远安全。
 * - 误判率：1%（falseProbability=0.01）。误判率越低所需位数组越大、占内存越多；防穿透场景
 *   误判只是"多放行一次走缓存/DB 空值兜底"，1% 已足够，无需更低。
 * - 持久化：Redisson 布隆过滤器数据直接落 Redis（key=bloom:attraction:），应用重启不丢失；
 *   tryInit 仅在 Redis 中尚无该过滤器时创建（返回 true），已存在则保留原数据（返回 false），不重建。
 */
@Slf4j
@Service
public class AttractionBloomFilterService {

    /** 【布隆过滤器防穿透的实现】预估元素数量：预留扩容（当前 26 条，压测/演示可能到百级），1000 足够 */
    private static final long EXPECTED_INSERTIONS = 1000L;
    /** 【布隆过滤器防穿透的实现】误判率 1%：越低占内存越大，防穿透只需挡"确定不存在"，1% 够用 */
    private static final double FALSE_PROBABILITY = 0.01;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private AttractionMapper attractionMapper;

    private RBloomFilter<Object> bloomFilter;

    /**
     * 【布隆过滤器防穿透的实现】初始化布隆过滤器（应用启动时执行）
     * <p>
     * 实现细节：getBloomFilter 获取过滤器对象（非初始化），tryInit 只在 Redis 中不存在该 key 时
     * 按容量/误判率创建并返回 true；已存在则返回 false 且不动已有数据（重启后布隆数据不丢失）。
     * 首次创建后必须全量预热，否则重启后过滤器为空，所有查询都会被第一道防线误拦。
     */
    @PostConstruct
    public void init() {
        RBloomFilter<Object> filter = redissonClient.getBloomFilter(BLOOM_ATTRACTION_KEY);
        boolean freshInit = filter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);
        this.bloomFilter = filter;
        if (freshInit) {
            // 首次初始化（Redis 中此前无该过滤器）→ 全量预热现有景点 id
            warmUp();
        } else {
            log.info("【布隆过滤器防穿透的实现】bloom:attraction: 已存在，跳过预热（保留 Redis 中原有数据）");
        }
    }

    /**
     * 【布隆过滤器防穿透的实现】启动预热：把现有全部景点 id 批量 add 进布隆过滤器
     * <p>
     * 实现细节：attractionMapper.selectList(null) 取全表 id（26 条级别，量小直接全量）；
     * 仅在 init 判定"过滤器刚创建为空"时调用，已初始化的过滤器绝不重复预热，避免重复 add 浪费位。
     */
    private void warmUp() {
        List<Long> ids = attractionMapper.selectList(null).stream().map(a -> a.getId()).toList();
        for (Long id : ids) {
            bloomFilter.add(id);
        }
        log.info("【布隆过滤器防穿透的实现】布隆过滤器预热完成，共 add {} 个景点 id", ids.size());
    }

    /**
     * 【布隆过滤器防穿透的实现】判断景点 id 是否存在（第一道防穿透拦截入口）
     * <p>
     * 实现细节：false=布隆确定不存在（直接拦截，不查缓存/DB）；true=可能存在（放行至缓存/DB，
     * 其中包含 1% 误判——误判的 id 会被后续空值占位/DB null 兜底，安全）。
     * @param id 景点id
     * @return true=可能存在；false=确定不存在
     */
    public boolean contains(Long id) {
        return bloomFilter.contains(id);
    }

    /**
     * 【布隆过滤器防穿透的实现】把景点 id 写入布隆过滤器（景点新增时同步调用）
     * <p>
     * 实现细节：只增不删；新景点 save 回填 id 后调用，保证后续查询不被第一道防线误拦。
     * @param id 景点id
     */
    public void add(Long id) {
        bloomFilter.add(id);
    }
}
