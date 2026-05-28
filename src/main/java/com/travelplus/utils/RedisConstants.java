package com.travelplus.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_ATTRACTION_TTL = 30L;
    public static final String CACHE_ATTRACTION_KEY = "cache:attraction:";
    public static final String CACHE_TICKET_LIST_KEY = "cache:ticket:list:";

    public static final String LOCK_ATTRACTION_KEY = "lock:attraction:";
    public static final Long LOCK_ATTRACTION_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String NOTE_LIKED_KEY = "note:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String ATTRACTION_GEO_KEY = "attraction:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
