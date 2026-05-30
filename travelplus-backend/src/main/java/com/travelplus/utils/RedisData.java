package com.travelplus.utils;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 【逻辑过期缓存数据的实现】包装业务数据与其逻辑过期时间。
 * 由 CacheClient.setWithLogicalExpire 写入 Redis（JSON 序列化），
 * 读侧在 queryWithLogicalExpire 中反序列化后比较 expireTime 判断缓存是否逻辑过期。
 */
@Data
public class RedisData {
    /** 逻辑过期时间，晚于当前时间表示缓存未过期 */
    private LocalDateTime expireTime;
    /** 业务数据（序列化时存入 JSON，读回时再反序列化为目标类型） */
    private Object data;
}
