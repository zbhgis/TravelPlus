package com.travelplus.limiter.exception;

/**
 * 【限流的实现】限流异常：RateLimiterAspect 在执行滑动窗口限流 Lua 脚本返回 0（被限流）时抛出，
 * 携带注解配置的限流提示信息，最终由 WebExceptionAdvice 全局异常处理器转成 Result.fail 返回前端
 * （如景点按类型检索 5 秒 5 次的 "查询次数过多"）。
 */
public class RateLimitException extends RuntimeException {
    public RateLimitException(String message) {
        super(message);
    }
}