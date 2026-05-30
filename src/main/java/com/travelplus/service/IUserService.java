package com.travelplus.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.travelplus.dto.LoginFormDTO;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserEditDTO;
import com.travelplus.entity.User;

import jakarta.servlet.http.HttpSession;

/**
 * 【用户服务的实现】用户服务接口：验证码/密码登录、注册、签到、主页聚合与资料编辑
 * <p>
 * 实现细节：
 * - 登录/注册/密码登录三条链路统一收敛到 saveLoginToken，登录态存 Redis Hash（key=login:token:{token}），
 *   与 RefreshTokenInterceptor 续期、logout 删除共用同一 key 体系。
 * - 签到能力基于 Redis BitMap，key=sign:{userId}:yyyyMM，sign/signCount/signList 读写同一份位图。
 * - 主页数据实时统计（COUNT/SUM），关注态由 UserHolder 判空决定；资料编辑仅限本人。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 【验证码登录的实现】发送登录验证码
     * <p>
     * 实现细节：校验手机号格式（RegexUtils.isPhoneInvalid）→ 生成 6 位随机验证码 →
     * 存入 Redis key=login:code:{phone}，TTL=LOGIN_CODE_TTL 分钟；验证码仅打印到后端日志，
     * 前端登录时回填并与之比对。
     * @param phone   手机号
     * @param session 会话（兼容保留，验证码实际存 Redis）
     * @return 发送成功返回 ok
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 【密码登录与注册的实现】登录：password 非空走密码登录，否则回退验证码登录
     * <p>
     * 实现细节：双分支设计——密码分支先查手机号再 BCrypt.checkpw 校验密码；验证码分支校验 Redis 验证码，
     * 用户不存在时 createUserWithPhone 自动建号；两条链路均收敛到 saveLoginToken 返回 token。
     * @param loginForm 手机号 +（password 或 code）
     * @param session   会话（兼容保留）
     * @return Result.data=token
     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 【密码登录与注册的实现】注册：手机号+密码（≥6位）+可选昵称创建账号，注册成功自动登录
     * <p>
     * 实现细节：校验手机号/密码长度/手机号查重 → 密码 BCrypt.hashpw 加密存储 → save 落库 →
     * 复用 saveLoginToken 自动登录返回 token，一次请求完成"注册+登录"。
     * @param loginForm 手机号、密码、可选昵称
     * @return Result.data=token
     */
    Result register(LoginFormDTO loginForm);

    /**
     * 【签到功能的实现】当天签到
     * <p>
     * 实现细节：SETBIT sign:{userId}:yyyyMM 的 (dayOfMonth-1) 位为 1；重复签到幂等（同一位重写）。
     * @return 签到成功返回 ok
     */
    Result sign();

    /**
     * 【签到功能的实现】统计本月"连续签到"天数（从今天往前数）
     * <p>
     * 实现细节：BITFIELD GET u{dayOfMonth} 取本月位图，从 LSB（今天）右移逐天数连续签到位，遇 0 停止。
     * @return Result.data=连续签到天数
     */
    Result signCount();

    /**
     * 【用户主页的实现】聚合用户主页：基础信息 + 实时社交统计 + 登录用户关注态
     * <p>
     * 实现细节：fans/followee/noteCount 实时 COUNT、likedCount 实时 SUM（COALESCE 兜 0）；
     * followed 由 UserHolder 判空决定（未登录=false）；详见 UserServiceImpl.queryUserHome。
     * @param id 被查看主页的用户id
     * @return Result.data=Map{user,info,fans,followee,likedCount,followed,noteCount}
     */
    Result queryUserHome(Long id);

    /**
     * 【签到功能的实现】本月签到日期数组（日历渲染用）
     * <p>
     * 实现细节：BITFIELD 读取本月位图，按位解析出已签到日期号，升序返回（未签到/无记录返回空数组）。
     * @return Result.data=List&lt;Integer&gt; 已签到日期号
     */
    Result signList();

    /**
     * 【用户主页编辑的实现】编辑个人资料/头像/背景图/隐私设置（仅限本人，详见实现类注释）
     */
    Result updateUserInfo(UserEditDTO dto);

}
