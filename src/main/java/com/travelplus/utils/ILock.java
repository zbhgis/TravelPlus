package com.travelplus.utils;

/**
 * 【分布式锁抽象的实现】定义分布式锁的获取/释放统一接口。
 * 具体实现为 SimpleRedisLock（基于 Redis setIfAbsent + Lua 脚本原子释放），
 * 供秒杀下单等需要「一人一单」/缓存重建等临界区保护的场景使用。
 */
public interface ILock {

    /**
     * 尝试获取锁
     * @param timeoutSec 锁持有的超时时间，过期后自动释放
     * @return true代表获取锁成功; false代表获取锁失败
     */
    boolean tryLock(long timeoutSec);

    /**
     * 释放锁
     */
    void unlock();
}
