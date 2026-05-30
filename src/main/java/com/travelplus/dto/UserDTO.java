package com.travelplus.dto;

import lombok.Data;

/**
 * 【用户信息传输的实现】返回给前端的用户视图对象（非表实体）：
 * 登录成功后存入 Redis Hash（login:token:{token}）并写入 UserHolder 供各业务取用，
 * 也用于游记/评论/关注列表等场景展示作者昵称与头像。字段含默认值，避免 BeanUtil.beanToMap
 * 对 null 字段调 toString() 抛 NPE（详见字段注释）。
 */
/**
 * 【用户信息传输对象】对外暴露的用户脱敏视图，只含展示所需字段，绝不携带 phone/password
 * <p>
 * 实现细节：
 * - 通过 BeanUtil.copyProperties 从 User 拷贝生成，用于登录态缓存（Redis Hash）、
 *   用户公开信息、关注/粉丝/点赞用户列表等场景。
 * - bgImage/isFollowed 默认非 null：避免 saveLoginToken 的 beanToMap + setFieldValueEditor(toString)
 *   对 null 字段调用 toString() 抛 NPE（见下方字段注释），同时让 JSON 序列化更干净。
 */
@Data
public class UserDTO {
    private Long id;
    private String nickName;
    private String icon;
    /**
     * 【用户主页编辑的实现】用户背景图路径（主页 user 字段回显用）
     * 默认 "" 而非 null：避免 saveLoginToken 的 BeanUtil.beanToMap + setFieldValueEditor(toString)
     * 对 null 字段调用 toString() 抛 NPE（与 icon 的默认值处理一致）。
     */
    private String bgImage = "";
    /**
     * 当前登录用户是否已关注该用户（关注/粉丝列表页展示用）
     * 默认 false 而非 null：避免 JSON 序列化出现 null 字段，也避免 saveLoginToken 的
     * BeanUtil.beanToMap + setFieldValueEditor(toString) 对 null 字段调用 toString() 抛 NPE（登录链路）。
     */
    private Boolean isFollowed = false;
}
