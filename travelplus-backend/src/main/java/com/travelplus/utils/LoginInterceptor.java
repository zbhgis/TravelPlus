package com.travelplus.utils;

import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 【登录拦截的实现】校验请求是否已登录的拦截器（在 RefreshTokenInterceptor 之后执行）。
 * 登录态判断依赖 RefreshTokenInterceptor 已写入 UserHolder 的用户信息，而不是直接读取 token：
 * 1) CORS 预检请求（OPTIONS）直接放行，避免浏览器预检不带业务凭证导致 401；
 * 2) UserHolder 中无用户 → 返回 401 状态码并拦截；
 * 3) 有用户 → 放行。
 * 放行路径（/user/register、/imgs/**、/error 等）由 MvcConfig 的 excludePathPatterns 排除，不进入本拦截器。
 */
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.CORS 预检请求（OPTIONS）直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 2.判断是否需要拦截（ThreadLocal中是否有用户）
        if (UserHolder.getUser() == null) {
            // 没有，需要拦截，设置状态码
            response.setStatus(401);
            // 拦截
            return false;
        }
        // 有用户，则放行
        return true;
    }
}
