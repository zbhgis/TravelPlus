package com.travelplus.utils;

/**
 * 【正则表达式常量的实现】集中定义业务侧字段格式校验所需的正则，供 RegexUtils 调用。
 * 手机号：1 开头、第二位按运营商号段（3/4/5/6/7/8/9）匹配共 11 位；
 * 邮箱：用户名@域名.后缀的标准结构；密码：4~32 位字母/数字/下划线；验证码：6 位字母或数字。
 * @author TravelPlus
 */
public abstract class RegexPatterns {
    /**
     * 手机号正则
     */
    public static final String PHONE_REGEX = "^1([38][0-9]|4[579]|5[0-3,5-9]|6[6]|7[0135678]|9[89])\\d{8}$";
    /**
     * 邮箱正则
     */
    public static final String EMAIL_REGEX = "^[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$";
    /**
     * 密码正则。4~32位的字母、数字、下划线
     */
    public static final String PASSWORD_REGEX = "^\\w{4,32}$";
    /**
     * 验证码正则, 6位数字或字母
     */
    public static final String VERIFY_CODE_REGEX = "^[a-zA-Z\\d]{6}$";

}
