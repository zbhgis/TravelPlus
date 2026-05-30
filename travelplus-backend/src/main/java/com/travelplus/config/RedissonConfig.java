package com.travelplus.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 【Redis集群的实现】创建连接 Redis 主从 + 3 哨兵 高可用集群（哨兵集群 master=mymaster）的 RedissonClient Bean。
 * Redisson 提供开箱即用的分布式锁（RLock）、布隆过滤器等高级数据结构，供秒杀/缓存等并发场景使用。
 * 实现细节：
 * - useSentinelServers：连接哨兵集群（127.0.0.1:26379/26380/26381），通过 masterName=mymaster
 *   动态获取当前主节点地址；主节点宕机后哨兵自动完成故障转移，Redisson 自动感知并重连新主，锁不中断。
 * - setPassword("123456")：认证主/从节点的密码（docker/redis-sentinel 中 requirepass 统一 123456）；
 *   哨兵本身无密码，故不调用 setSentinelPassword，避免向哨兵误发 AUTH。
 * - 锁语义：Redisson RLock 天然走主节点（分布式锁要求强一致，绝不能在异步复制的从节点上加锁）；
 *   TicketOrderServiceImpl.createTicketOrder 消费端用 lock:order:{userId} 防同一用户并发重复下单。
 */
@Configuration
public class RedissonConfig {

    @Bean
    public RedissonClient redissonClient(){
        // 配置
        Config config = new Config();
        // 【Redis集群的实现】哨兵模式：masterName=mymaster，3 个哨兵地址；
        // Redisson 通过哨兵动态发现主节点，主从故障切换后自动重连新主，无需重启应用。
        config.useSentinelServers()
                .setMasterName("mymaster")
                .addSentinelAddress("redis://127.0.0.1:26379",
                        "redis://127.0.0.1:26380",
                        "redis://127.0.0.1:26381")
                .setPassword("123456"); // master/slave 认证密码（哨兵无密码，故不 setSentinelPassword）
        // 【Redis集群failover加固的实现】Redisson failover 期间的重试/连接行为说明：
        // - 连接池/超时/重试次数未做自定义，保持 Redisson 默认值（connectTimeout 10s、retryAttempts 3、
        //   retryInterval 1500ms），默认值对正常瞬时抖动已足够宽容，不必收紧。
        // - 此前故障转移瞬间 JVM 崩溃的根因是哨兵向 Redisson 通告了容器内网名 redis-master，导致
        //   UnknownHostException 反复解析 + 疯狂重建连接耗尽资源；该缝隙已由哨兵配置加固
        //   （sentinel resolve-hostnames no / announce-hostnames no + monitor/announce 全程用宿主可达
        //   IP 172.30.144.1，见 docker/redis-sentinel/sentinel*.conf）在通告侧根治，Redisson 侧无需再兜底。
        // 创建RedissonClient对象
        return Redisson.create(config);
    }
}
