package com.travelplus.utils;

import com.travelplus.dto.UserDTO;

/**
 * 【用户上下文持有的实现】基于 ThreadLocal 保存当前请求线程的登录用户信息。
 * 由 RefreshTokenInterceptor 在解析 token 成功后写入，LoginInterceptor 据此判断登录态，
 * 业务层通过 UserHolder.getUser() 获取当前用户；请求结束由拦截器 afterCompletion 调用 removeUser() 清理，避免线程复用导致数据串线。
 */
public class UserHolder {
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    /**
     * 保存当前线程的登录用户
     * @param user 当前登录用户（UserDTO）
     */
    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    /**
     * 获取当前线程的登录用户
     * @return 当前登录用户，未登录时为 null
     */
    public static UserDTO getUser(){
        return tl.get();
    }

    /**
     * 移除当前线程的登录用户（请求结束后清理，防止 ThreadLocal 内存泄漏）
     */
    public static void removeUser(){
        tl.remove();
    }
}
