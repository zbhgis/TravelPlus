package com.travelplus.dto;

import lombok.Data;

import java.time.LocalDate;

/**
 * <p>
 * 【用户主页编辑的实现】个人资料编辑入参 DTO
 * </p>
 * <p>
 * 实现细节：
 * - 与 tb_user/tb_user_info 两张表的可编辑字段一一对应：tb_user 的 nickName/icon/bgImage，
 *   tb_user_info 的 introduce/city/gender/birthday/followVisible/fansVisible。
 * - 字段均允许为空：service 层只更新非空字段（前端"部分保存"时未提交的字段不会被覆盖）。
 * - gender 用 Integer（0/1）接收：JSON 布尔 true/false 会被 Jackson 自动强转为 1/0，容错两种前端传法。
 * - birthday 用 LocalDate：接收 "1995-06-15" 这类 ISO 日期字符串（Spring Boot 默认 JavaTimeModule 支持）。
 * </p>
 */
@Data
public class UserEditDTO {

    /**
     * 昵称（非空时长度需 ≤20）
     */
    private String nickName;

    /**
     * 头像路径（非空才更新）
     */
    private String icon;

    /**
     * 背景图路径（非空才更新）
     */
    private String bgImage;

    /**
     * 个人介绍（tb_user_info.introduce）
     */
    private String introduce;

    /**
     * 城市（tb_user_info.city）
     */
    private String city;

    /**
     * 性别：0=男，1=女（仅 0/1 合法）
     */
    private Integer gender;

    /**
     * 生日（tb_user_info.birthday）
     */
    private LocalDate birthday;

    /**
     * 【隐私设置的实现】关注列表是否公开：1=可见，0=不可见
     */
    private Integer followVisible;

    /**
     * 【隐私设置的实现】粉丝列表是否公开：1=可见，0=不可见
     */
    private Integer fansVisible;
}
