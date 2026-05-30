package com.travelplus.utils;

/**
 * 【Redis key 与过期时间的实现】集中定义 Redis 中使用的 key 前缀、TTL 与限制值。
 * key 设计遵循「业务域:类型:标识」三段式，方便按前缀批量管理与排查：
 * - login:code:{phone}：短信验证码；login:token:{token}：登录态（Hash 存用户信息，TTL 滚动续期）
 * - cache:attraction:{id}：景点缓存；cache:ticket:list:{attractionId}：门票列表缓存
 * - bloom:attraction:：景点布隆过滤器（详情查询第一道防穿透防线，只增不删）
 * - lock:attraction:{id}：缓存重建互斥锁；seckill:stock:{ticketId}：秒杀库存
 * - note:liked:{noteId}：游记点赞集合（ZSet）；attraction:liked:{attractionId}：景点点赞集合（ZSet）；
 *   feed:{userId}：关注推送收件箱（ZSet）
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
    /** 【缓存雪崩预防-错峰过期的实现】TTL 随机抖动范围（秒）：基准 TTL 上浮 0~300 秒（5 分钟），
     * 同批写入的 key 过期时刻被随机打散，避免集中失效回源 DB。
     * 在 CacheClient.set() 统一入口叠加，所有走 set() 的缓存自动生效；
     * 空值占位（CACHE_NULL_TTL 直调 stringRedisTemplate.set）与逻辑过期（无物理 TTL）不受影响。 */
    public static final Long CACHE_TTL_JITTER_SECONDS = 60L;
    /** 景点缓存 key 前缀：cache:attraction:{id} */
    public static final String CACHE_ATTRACTION_KEY = "cache:attraction:";
    /** 【布隆过滤器防穿透的实现】景点布隆过滤器 key：bloom:attraction:。
     * 第一道防穿透防线：详情查询先经布隆过滤器判定 id 是否存在，确定不存在直接拦截（不查缓存/DB），
     * 与 cache:attraction:{id} 空值占位（第二道）配合，见 AttractionBloomFilterService。 */
    public static final String BLOOM_ATTRACTION_KEY = "bloom:attraction:";
    /** 门票列表缓存 key 前缀：cache:ticket:list:{attractionId} */
    public static final String CACHE_TICKET_LIST_KEY = "cache:ticket:list:";

    /** 缓存重建互斥锁 key 前缀：lock:attraction:{id} */
    public static final String LOCK_ATTRACTION_KEY = "lock:attraction:";
    /** 互斥锁自动释放时间（秒），避免持有方宕机导致死锁 */
    public static final Long LOCK_ATTRACTION_TTL = 10L;

    /** 秒杀库存 key 前缀：seckill:stock:{ticketId} */
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    /** 秒杀下单集合 key 前缀：seckill:order:{ticketId}（Set，成员=已下单用户id，seckill.lua SADD 实现一人一单防重；
     * 订单超时取消时 SREM 移除该成员即释放秒杀资格，用户可重新秒杀） */
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    /** 游记点赞用户集合 key 前缀：note:liked:{noteId}（ZSet，score=点赞时间戳，供点赞榜排序） */
    public static final String NOTE_LIKED_KEY = "note:liked:";
    /** 【景点点赞的实现】景点点赞用户集合 key 前缀：attraction:liked:{attractionId}（ZSet，score=点赞时间戳）。
     * 与游记点赞同构（note:liked:{id}），成员=userId，score=System.currentTimeMillis()；
     * 景点无 liked 计数列（tb_attraction 无 liked 字段），赞过判定/赞过列表全走该 ZSet，见 AttractionServiceImpl.likeAttraction 注释。 */
    public static final String ATTRACTION_LIKED_KEY = "attraction:liked:";
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

    /** 私信陌生人单条 key 前缀：chat:stranger:{fromUserId}:{toUserId}:{yyyyMMdd}（String 计数，TTL=当日剩余秒数）。
     * 【私信限制的实现】用于"对方回复前每日最多 1 条"的防骚扰限制——非互关且对方未回复我时，
     * 对同一接收者每日首次发信计数=1 放行，计数>1 即 fail；对方一旦回复过（或互关）则不再受限。 */
    public static final String CHAT_STRANGER_KEY = "chat:stranger:";
}
