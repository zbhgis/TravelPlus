package com.travelplus.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.travelplus.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.travelplus.utils.RedisConstants.LOGIN_USER_KEY;
import static com.travelplus.utils.RedisConstants.LOGIN_USER_TTL;

/**
 * 【token刷新的实现】基于 Redis 的 token 解析与续期拦截器（在 LoginInterceptor 之前执行，注册顺序见 MvcConfig）。
 * 逻辑：
 * 1) 从请求头 authorization 读取 token，为空则放行（交给后续 LoginInterceptor 决定是否拦截）；
 * 2) 以 login:token:{token} 为 key 查询 Redis Hash 中的用户信息；
 * 3) Hash 为空说明 token 失效，放行但不清除登录态；
 * 4) 存在则将 Hash 转 UserDTO 存入 UserHolder，并以 LOGIN_USER_TTL（30 分钟）滚动刷新 token 有效期；
 * 5) afterCompletion 清理 UserHolder，防止线程复用串数据。
 */
public class RefreshTokenInterceptor implements HandlerInterceptor {

    private StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1.获取请求头中的token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }
        // 2.基于TOKEN获取redis中的用户
        String key  = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        // 3.判断用户是否存在
        if (userMap.isEmpty()) {
            return true;
        }
        // 5.将查询到的hash数据转为UserDTO
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 6.存在，保存用户信息到 ThreadLocal
        UserHolder.saveUser(userDTO);
        // 7.刷新token有效期
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 8.放行
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // 移除用户
        UserHolder.removeUser();
    }
}
