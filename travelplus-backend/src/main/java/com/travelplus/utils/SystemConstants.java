package com.travelplus.utils;

/**
 * 【系统常量定义的实现】集中存放全局业务常量：图片上传目录、默认昵称前缀与分页大小。
 * 图片上传目录默认指向项目运行目录下 uploads/imgs/（实际静态映射由 MvcConfig 的 /imgs/** 资源映射决定）；
 * 用户未设置昵称时使用 USER_NICK_NAME_PREFIX + userId 生成默认昵称；分页列表默认每页 DEFAULT_PAGE_SIZE 条。
 */
public class SystemConstants {
    /** 图片上传目录（相对运行目录） */
    public static final String IMAGE_UPLOAD_DIR = System.getProperty("user.dir") + "\\uploads\\imgs\\";
    /** 默认用户昵称前缀，注册时拼接 userId 生成默认昵称 */
    public static final String USER_NICK_NAME_PREFIX = "user_";
    /** 列表分页默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 5;
    /** 列表分页允许的最大每页条数 */
    public static final int MAX_PAGE_SIZE = 10;
    /** 【订单超时的实现】秒杀订单支付超时时间（分钟）：用户秒杀下单成功后须在 15 分钟内付款，
     * 超时未支付由 OrderTimeoutTask 定时任务自动取消（订单 status 1→4）+ 回补库存（DB+Redis）+ 释放秒杀资格 */
    public static final int PAY_TIMEOUT_MINUTES = 15;
}
