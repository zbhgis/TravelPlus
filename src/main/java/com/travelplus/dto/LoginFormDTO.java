package com.travelplus.dto;

import lombok.Data;

/**
 * 【登录/注册入参的实现】POST /user/login 与 /user/register 的请求体 DTO。
 * 登录走手机号 + 验证码（phone/code）或手机号 + 密码（phone/password）；
 * 注册时可额外传 nickName，为空时服务端自动生成 "user_" + 随机串 作为默认昵称。
 */
/**
 * 【登录/注册入参封装】登录与注册共用的表单 DTO
 * <p>
 * 实现细节：
 * - 三种用法按字段组合区分：验证码登录 {phone, code}；密码登录 {phone, password}；
 *   注册 {phone, password, nickName?}。服务端以 password 是否非空区分密码登录与验证码登录。
 * - code 仅验证码登录使用，password 仅密码登录/注册使用，互斥出现。
 */
@Data
public class LoginFormDTO {
    private String phone;
    private String code;
    private String password;
    /**
     * 昵称（注册时可选传；为空时服务端自动生成 "user_" + 随机串）
     */
    private String nickName;
}
