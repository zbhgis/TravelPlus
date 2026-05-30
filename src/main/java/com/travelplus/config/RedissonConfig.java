package com.travelplus.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 【Redisson 客户端配置的实现】创建连接本地 Redis（redis://127.0.0.1:6379）的单节点 RedissonClient Bean。
 * Redisson 提供开箱即用的分布式锁（RLock）、布隆过滤器等高级数据结构，供秒杀/缓存等并发场景使用
 * （注：本项目秒杀下单实际使用 utils 包 SimpleRedisLock 自研锁，本配置作为 Redisson 能力的备用接入点）。
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
