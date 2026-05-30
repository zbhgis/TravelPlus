package com.travelplus.utils;


import cn.hutool.core.util.RandomUtil;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

/**
 * 【密码加密的实现】加盐 MD5 密码工具：encode 随机生成 20 位盐并计算 MD5，最终存「盐@加密串」。
 * matches 校验时从存储串中拆出盐并重算比对，避免相同明文产生相同摘要（彩虹表/撞库防护）。
 * 存储格式：{salt}@{md5(password+salt)}，utf-8 编码后做 MD5 十六进制摘要。
 */
public class PasswordEncoder {

    /**
     * 加密：随机生成盐后执行加盐 MD5
     * @param password 明文密码
     * @return {盐}@{MD5(密码+盐)}
     */
    public static String encode(String password) {
        // 生成盐
        String salt = RandomUtil.randomString(20);
        // 加密
        return encode(password,salt);
    }
    private static String encode(String password, String salt) {
        // 加密
        return salt + "@" + DigestUtils.md5DigestAsHex((password + salt).getBytes(StandardCharsets.UTF_8));
    }
    /**
     * 校验密码：从存储串中提取盐并重算比对
     * @param encodedPassword 存储的 {盐}@{MD5} 串
     * @param rawPassword     待校验的明文密码
     * @return 是否匹配；存储串格式不正确时抛 RuntimeException
     */
    public static Boolean matches(String encodedPassword, String rawPassword) {
        if (encodedPassword == null || rawPassword == null) {
            return false;
        }
        if(!encodedPassword.contains("@")){
            throw new RuntimeException("密码格式不正确！");
        }
        String[] arr = encodedPassword.split("@");
        // 获取盐
        String salt = arr[0];
        // 比较
        return encodedPassword.equals(encode(rawPassword, salt));
    }
}
