package com.travelplus.config;

import com.travelplus.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 【全局异常处理的实现】@RestControllerAdvice 统一捕获 Controller 层抛出的 RuntimeException，
 * 记录 error 级日志（含完整堆栈，便于排查），并返回 Result.fail("服务器异常:" + 异常信息) 给前端。
 * 业务上通过抛特定异常（如限流 RateLimitException 等）即可在此统一收敛为前端可读的失败响应。
 */
@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    // 全局异常处理捕捉，记录通用日志，返回给前端通用响应 Result.fail("服务器异常")
    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error(e.toString(), e);
        return Result.fail("服务器异常:" + e.getMessage());
    }
}
