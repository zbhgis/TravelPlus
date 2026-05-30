package com.travelplus.utils;

import cn.hutool.core.util.StrUtil;

/**
 * 【格式校验工具的实现】基于 RegexPatterns 中预定义的正则，对外提供手机号/邮箱/验证码格式校验。
 * 统一走 mismatch() 私有方法：入参为空或空串一律视为「无效」，非空则用 String.matches 全量匹配正则。
 * 命名语义为 isXxxInvalid，返回 true 表示格式不合法（业务侧据此直接拒绝或提示），供登录/注册/验证码发送流程复用。
 * @author TravelPlus
 */
public class RegexUtils {
    /**
     * 是否是无效手机格式
     * @param phone 要校验的手机号
     * @return true:符合，false：不符合
     */
    public static boolean isPhoneInvalid(String phone){
        return mismatch(phone, RegexPatterns.PHONE_REGEX);
    }
    /**
     * 是否是无效邮箱格式
     * @param email 要校验的邮箱
     * @return true:符合，false：不符合
     */
    public static boolean isEmailInvalid(String email){
        return mismatch(email, RegexPatterns.EMAIL_REGEX);
    }

    /**
     * 是否是无效验证码格式
     * @param code 要校验的验证码
     * @return true:符合，false：不符合
     */
    public static boolean isCodeInvalid(String code){
        return mismatch(code, RegexPatterns.VERIFY_CODE_REGEX);
    }

    // 校验是否不符合正则格式
    private static boolean mismatch(String str, String regex){
        if (StrUtil.isBlank(str)) {
            return true;
        }
        return !str.matches(regex);
    }
}
