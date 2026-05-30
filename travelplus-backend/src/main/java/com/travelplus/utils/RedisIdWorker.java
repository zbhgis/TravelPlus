package com.travelplus.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 【ID生成的实现】基于 Redis 自增计数与时间戳拼接的全局唯一 ID 生成器（雪花算法替代方案）。
 * 生成规则：64 位 long，高 32 位为「当前秒数 - 起始时间戳(2022-01-01 00:00:00)」的差值，低 32 位为当天自增序列号。
 * 序列号通过 Redis 的 INCR 原子自增（key: icr:{业务前缀}:{yyyy:MM:dd}，按天隔离使序列号从 0 重新开始），
 * 避免分布式环境下 ID 冲突，同时保证趋势递增便于索引；Redis 单次自增的原子性保证并发安全。
 */
@Component
public class RedisIdWorker {
    /**
     * 开始时间戳（2022-01-01 00:00:00 的秒数），用于压缩时间戳部分
     */
    private static final long BEGIN_TIMESTAMP = 1640995200L;
    /**
     * 序列号的位数（32 位），与时间戳部分各占 long 的一半
     */
    private static final int COUNT_BITS = 32;

    private StringRedisTemplate stringRedisTemplate;

    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 生成下一个全局唯一 ID
     * @param keyPrefix 业务前缀（如订单/游记等），用于隔离不同业务的序列号计数
     * @return 时间戳(32位) | 当天自增序列号(32位) 拼接的 long
     */
    public long nextId(String keyPrefix) {
        // 1.生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;

        // 2.生成序列号
        // 2.1.获取当前日期，精确到天
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 2.2.自增长
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);

        // 3.拼接并返回
        return timestamp << COUNT_BITS | count;
    }
}
