package com.travelplus.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.travelplus.utils.RedisConstants.CACHE_NULL_TTL;
import static com.travelplus.utils.RedisConstants.CACHE_TTL_JITTER_SECONDS;
import static com.travelplus.utils.RedisConstants.LOCK_ATTRACTION_KEY;

/**
 * 【缓存工具的实现】封装 Redis 缓存的写入与三种缓存问题（穿透/击穿/逻辑过期）的读取方案：
 * 1) queryWithPassThrough —— 缓存穿透：缓存与数据库均无数据时写入空值占位（CACHE_NULL_TTL=2 分钟），避免恶意 key 打爆 DB；
 * 2) queryWithMutex —— 缓存击穿：缓存过期瞬间用 setIfAbsent 互斥锁（lock:attraction:{id}，10 秒自动过期）限制仅一个线程回源重建缓存，其余线程休眠 50ms 递归重试；
 * 3) queryWithLogicalExpire —— 缓存击穿（逻辑过期）：缓存永不过期，写入时带 expireTime 字段，读时发现逻辑过期则异步线程池 CACHE_REBUILD_EXECUTOR（10 线程）重建缓存并返回旧数据，保证高并发下接口不阻塞。
 * 所有写入统一走 set() 的 JSON 序列化（hutool JSONUtil），读回时按 Class 反序列化。
 */
@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    /** 逻辑过期方案的缓存重建线程池：固定 10 线程，异步回源数据库重建缓存 */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 【缓存雪崩预防-错峰过期的实现】普通写入：将 value 序列化为 JSON 存入 Redis，TTL 在基准上叠加随机抖动错峰过期。
     * 实现细节：
     * - 为什么统一在 set()：所有缓存写入（queryWithPassThrough / queryWithMutex 的写缓存均走本方法）自动错峰，
     *   各调用方无需感知，基准 TTL 不变（30 分钟景点 / 10 分钟门票），仅过期时刻被随机打散，避免同批 key 同时失效集中回源 DB。
     * - 为什么用 ThreadLocalRandom：高并发下避免 Math.random() 的全局原子竞争（CAS 自旋），每线程独立种子无共享状态，性能更好且无副作用。
     * - 随机范围 0~60 秒（基准 30 分钟的 ~3%）：太小效果差（同批 key 仍集中失效），
     *   太大缓存提前失效频繁回源 DB 造成不必要的 DB 压力，60 秒为经验折中值。
     * - 空值占位不受影响：CACHE_NULL_TTL 占位在 queryWithPassThrough/queryWithMutex 内直调 stringRedisTemplate.set，
     *   不走本方法，保持固定 2 分钟（尽快让真实数据可见）。
     * - 逻辑过期 setWithLogicalExpire 不走本方法：它写 RedisData 且 key 无物理 TTL（物理永不过期，靠 expireTime 字段判断），
     *   若误加物理 TTL 会破坏逻辑过期语义，故保持直调 stringRedisTemplate。
     * @param key   Redis key
     * @param value 任意对象，经 JSONUtil 序列化
     * @param time  过期时间（基准值）
     * @param unit  过期时间单位
     */
    public void set(String key, Object value, Long time, TimeUnit unit) {
        // 基准秒数 + 随机抖动（0~300 秒），最终以 SECONDS 写入，实现同批 key 错峰过期
        long finalSeconds = unit.toSeconds(time) + ThreadLocalRandom.current().nextLong(0, CACHE_TTL_JITTER_SECONDS);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), finalSeconds, TimeUnit.SECONDS);
    }

    /**
     * 【缓存雪崩预防-错峰过期的实现】逻辑过期写入：封装为 RedisData（data + expireTime）写入，key 本身不设置物理过期。
     * 注意：本方法直调 stringRedisTemplate，不走 set()——key 无物理 TTL（靠 expireTime 字段判断逻辑过期），
     * 若经 set() 叠加随机物理 TTL 会破坏逻辑过期语义，故保持直调不受错峰随机化影响。
     * @param key   Redis key
     * @param value 业务数据
     * @param time  逻辑过期时长
     * @param unit  时长单位（换算成秒后加到当前时间）
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 【缓存穿透的读取方案】缓存未命中回源数据库，空值写入缓存占位
     * @param keyPrefix 缓存 key 前缀
     * @param id        业务 id，拼接成完整 key
     * @param type      反序列化目标类型
     * @param dbFallback 数据库回源函数（按 id 查询）
     * @param time      有效数据 TTL
     * @param unit      TTL 单位
     * @return 业务数据；缓存为空值占位或数据不存在时返回 null
     */
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        // 1.从redis查询景点缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(json)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(json, type);
        }
        // 判断命中的是否是空值
        if (json != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        // 5.不存在，返回错误
        if (r == null) {
            // 将空值写入redis
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            // 返回错误信息
            return null;
        }
        // 6.存在，写入redis
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 【缓存击穿-逻辑过期的读取方案】缓存未过期直接返回旧数据，逻辑过期后异步重建缓存
     * @param keyPrefix 缓存 key 前缀
     * @param id        业务 id
     * @param type      反序列化目标类型
     * @param dbFallback 数据库回源函数
     * @param time      逻辑过期时长
     * @param unit      时长单位
     * @return 缓存中数据；缓存未命中（key 不存在）时返回 null
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询景点缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isBlank(json)) {
            // 3.存在，直接返回
            return null;
        }
        // 4.命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 5.判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            // 5.1.未过期，直接返回景点信息
            return r;
        }
        // 5.2.已过期，需要缓存重建
        // 6.缓存重建
        // 6.1.获取互斥锁
        String lockKey = LOCK_ATTRACTION_KEY + id;
        boolean isLock = tryLock(lockKey);
        // 6.2.判断是否获取锁成功
        if (isLock){
            // 6.3.成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    R newR = dbFallback.apply(id);
                    // 重建缓存
                    this.setWithLogicalExpire(key, newR, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    // 释放锁
                    unlock(lockKey);
                }
            });
        }
        // 6.4.返回过期的景点信息
        return r;
    }

    /**
     * 【缓存击穿-互斥锁的读取方案】缓存过期后通过 Redis 互斥锁串行化回源，避免热点 key 同时打穿数据库
     * @param keyPrefix 缓存 key 前缀
     * @param id        业务 id
     * @param type      反序列化目标类型
     * @param dbFallback 数据库回源函数
     * @param time      有效数据 TTL
     * @param unit      TTL 单位
     * @return 业务数据；空值占位或数据不存在时返回 null
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1.从redis查询景点缓存
        String attractionJson = stringRedisTemplate.opsForValue().get(key);
        // 2.判断是否存在
        if (StrUtil.isNotBlank(attractionJson)) {
            // 3.存在，直接返回
            return JSONUtil.toBean(attractionJson, type);
        }
        // 判断命中的是否是空值
        if (attractionJson != null) {
            // 返回一个错误信息
            return null;
        }

        // 4.实现缓存重建
        // 4.1.获取互斥锁
        String lockKey = LOCK_ATTRACTION_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            // 4.2.判断是否获取成功
            if (!isLock) {
                // 4.3.获取锁失败，休眠并重试
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 4.4.获取锁成功，根据id查询数据库
            r = dbFallback.apply(id);
            // 5.不存在，返回错误
            if (r == null) {
                // 将空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                // 返回错误信息
                return null;
            }
            // 6.存在，写入redis
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 7.释放锁
            unlock(lockKey);
        }
        // 8.返回
        return r;
    }

    /**
     * 尝试获取互斥锁：Redis setIfAbsent 原子设置，10 秒自动过期防止死锁
     * @param key 锁 key（lock:attraction:{id}）
     * @return 是否获取锁成功
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放互斥锁：直接删除锁 key
     * @param key 锁 key
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
