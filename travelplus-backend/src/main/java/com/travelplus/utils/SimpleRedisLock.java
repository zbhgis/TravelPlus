package com.travelplus.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * 【分布式锁的实现】基于 Redis 的互斥锁，实现 ILock 接口，用于秒杀下单「一人一单」等临界区保护。
 * tryLock：以 lock:{name} 为 key、setIfAbsent 原子写入「UUID前缀 + 线程id」作为锁标识，超时自动释放防止死锁；
 * unlock：执行 unlock.lua 脚本（get + 比对标识 + delete），保证「判断持有者」与「删除锁」原子完成，
 * 避免业务阻塞超时后误删他人已重新获取的锁（已注释的旧实现即存在该竞态问题）。
 */
public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /** 锁 key 前缀：lock:{业务名} */
    private static final String KEY_PREFIX = "lock:";
    /** 线程标识前缀：进程内唯一 UUID（不带横线），保证不同 JVM 的线程标识不冲突 */
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    /** 解锁 Lua 脚本：比对锁标识一致才删除，保证判断与删除的原子性 */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    /**
     * 尝试获取锁（原子 setIfAbsent）
     * @param timeoutSec 锁持有超时时间（秒），过期后 Redis 自动释放，防止持锁方宕机死锁
     * @return true 获取成功；false 获取失败（已有其他线程持锁）
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 释放锁：通过 Lua 脚本原子比对锁标识后删除，防止误删他人锁
     */
    @Override
    public void unlock() {
        // 调用lua脚本
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }
    /*@Override
    public void unlock() {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁中的标示
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 判断标示是否一致
        if(threadId.equals(id)) {
            // 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
