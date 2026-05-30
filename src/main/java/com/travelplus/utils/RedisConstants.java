package com.travelplus.utils;

/**
 * 【Redis key 与过期时间的实现】集中定义 Redis 中使用的 key 前缀、TTL 与限制值。
 * key 设计遵循「业务域:类型:标识」三段式，方便按前缀批量管理与排查：
 * - login:code:{phone}：短信验证码；login:token:{token}：登录态（Hash 存用户信息，TTL 滚动续期）
 * - cache:attraction:{id}：景点缓存；cache:ticket:list:{attractionId}：门票列表缓存
 * - lock:attraction:{id}：缓存重建互斥锁；seckill:stock:{ticketId}：秒杀库存
 * - note:liked:{noteId}：游记点赞集合（Set）；feed:{userId}：关注推送收件箱（ZSet）
 * - sign:{userId}:{yyyyMM}：签到位图；browse:{userId}：浏览记录（List）；attraction:geo:{typeId}：景点 GEO 坐标
 * TTL 单位均为分钟（除 BROWSE_RECORD_MAX_SIZE 外），注意 LOGIN_USER_TTL=36000 分钟即 30 天。
 */
public class RedisConstants {
    /** 短信验证码 key 前缀：login:code:{phone} */
    public static final String LOGIN_CODE_KEY = "login:code:";
    /** 短信验证码有效期（分钟） */
    public static final Long LOGIN_CODE_TTL = 2L;
    /** 登录 token key 前缀：login:token:{token}，Hash 中存用户信息 */
    public static final String LOGIN_USER_KEY = "login:token:";
    /** 登录 token 有效期（分钟），刷新登录时滚动续期 */
    public static final Long LOGIN_USER_TTL = 36000L;

    /** 缓存穿透时空值占位的 TTL（分钟） */
    public static final Long CACHE_NULL_TTL = 2L;

    /** 景点缓存的 TTL（分钟） */
    public static final Long CACHE_ATTRACTION_TTL = 30L;
    /** 景点缓存 key 前缀：cache:attraction:{id} */
    public static final String CACHE_ATTRACTION_KEY = "cache:attraction:";
    /** 门票列表缓存 key 前缀：cache:ticket:list:{attractionId} */
    public static final String CACHE_TICKET_LIST_KEY = "cache:ticket:list:";

    /** 缓存重建互斥锁 key 前缀：lock:attraction:{id} */
    public static final String LOCK_ATTRACTION_KEY = "lock:attraction:";
    /** 互斥锁自动释放时间（秒），避免持有方宕机导致死锁 */
    public static final Long LOCK_ATTRACTION_TTL = 10L;

    /** 秒杀库存 key 前缀：seckill:stock:{ticketId} */
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    /** 游记点赞用户集合 key 前缀：note:liked:{noteId}（Set） */
    public static final String NOTE_LIKED_KEY = "note:liked:";
    /** 关注推送收件箱 key 前缀：feed:{userId}（ZSet，按时间倒序） */
    public static final String FEED_KEY = "feed:";
    /** 景点 GEO 坐标 key 前缀：attraction:geo:{typeId}（GEO 按距离排序检索附近景点） */
    public static final String ATTRACTION_GEO_KEY = "attraction:geo:";
    /** 用户签到位图 key 前缀：sign:{userId}:{yyyyMM} */
    public static final String USER_SIGN_KEY = "sign:";
    /** 用户浏览记录 key 前缀：browse:{userId}（List） */
    public static final String BROWSE_RECORD_KEY = "browse:";
    /** 浏览记录保存的最大条数，超过则裁剪 */
    public static final int BROWSE_RECORD_MAX_SIZE = 50;
}
