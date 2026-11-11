package com.travelplus.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.travelplus.dto.LoginFormDTO;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserEditDTO;
import com.travelplus.entity.User;

import jakarta.servlet.http.HttpSession;

import java.math.BigDecimal;

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
     * 【签到积分的实现】当天签到：签到位图置 1 + 积分 +2 + 积分流水落库
     * <p>
     * 实现细节：先 GETBIT 检查今天是否已签（已签 fail"今天已签到，明天再来吧"，修复原幂等
     * SETBIT 的重复签到 bug）；未签则 SETBIT sign:{userId}:yyyyMM 的 (dayOfMonth-1) 位为 1，
     * 再 SQL 原子自增 tb_user.points=points+2 并 INSERT tb_points_record(+2, type='sign')，
     * 详见 UserServiceImpl.sign 注释。
     * @return 签到成功返回 ok；今天已签返回 fail
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
     * 【签到功能的实现】指定月份的签到日期数组（日历渲染用，缺省=本月）
     * <p>
     * 实现细节：BITFIELD 读取该月位图（key=sign:{userId}:yyyyMM），按位解析出已签到日期号，
     * 升序返回（未签到/无记录/未来月份返回空数组）。month 参数供前端日历翻看历史月份。
     * @param month 目标月份，格式 yyyyMM（如 202610）；null/空/非法格式回退本月
     * @return Result.data=List&lt;Integer&gt; 已签到日期号
     */
    Result signList(String month);

    /**
     * 【钱包资产的实现】当前登录用户钱包资产聚合：余额 / 积分 / 秒杀券数量
     * <p>
     * 实现细节：查 tb_user 的 balance（元）/points；couponCount = COUNT(tb_ticket_order
     * WHERE user_id=当前用户 AND status IN (2,3))（已支付/已核销的秒杀券数），
     * 详见 UserServiceImpl.queryWallet 注释。
     * @return Result.data=Map{balance, points, couponCount}
     */
    Result queryWallet();

    /**
     * 【积分记录的实现】当前登录用户积分增减记录（分页，钱包页"积分明细"）
     * <p>
     * 实现细节：查 tb_points_record WHERE user_id=当前用户 按 create_time 倒序分页
     * （每页 SystemConstants.MAX_PAGE_SIZE 条）；typeText 映射（sign=签到奖励 order=下单抵扣
     * refund=退款返还 admin=运营调整），详见 UserServiceImpl.queryPointsRecords 注释。
     * @param current 页码，默认第1页
     * @return data=List<Map{id, changePoints, type, typeText, description, createTime}>，total=流水总数
     */
    Result queryPointsRecords(Integer current);

    /**
     * 【余额流水的实现】当前登录用户余额增减记录（分页，钱包页"余额明细"）
     * <p>
     * 实现细节：查 tb_balance_record WHERE user_id=当前用户 按 create_time 倒序分页
     * （每页 SystemConstants.MAX_PAGE_SIZE 条）；typeText 映射（recharge=充值 pay=消费
     * refund=退款 admin=调整），详见 UserServiceImpl.queryBalanceRecords 注释。
     * @param current 页码，默认第1页
     * @return data=List<Map{id, changeAmount, type, typeText, payMethod, description, createTime}>，total=流水总数
     */
    Result queryBalanceRecords(Integer current);

    /**
     * 【充值功能的实现】模拟充值：微信/支付宝二选一，模拟直接成功
     * <p>
     * 实现细节：amount>0 且 ≤5000（元）、payMethod 仅 wechat/ali 合法；校验通过即模拟充值成功——
     * UPDATE tb_user SET balance=balance+amount（SQL 原子自增）并 INSERT tb_balance_record
     * （+amount, type='recharge', pay_method=payMethod），同一 @Transactional 原子提交，
     * 详见 UserServiceImpl.recharge 注释。
     * @param amount    充值金额（元）
     * @param payMethod 支付方式：wechat=微信 / ali=支付宝
     * @return 充值成功 ok（data=Map{balance=充值后余额}）；参数非法返回对应 fail
     */
    Result recharge(BigDecimal amount, String payMethod);

    /**
     * 【用户主页编辑的实现】编辑个人资料/头像/背景图/隐私设置（仅限本人，详见实现类注释）
     */
    Result updateUserInfo(UserEditDTO dto);

    /**
     * 【主页评论列表的实现】某用户的评论列表（分页，主页"评论" tab）
     * <p>
     * 实现细节：查 tb_travel_note_comment WHERE user_id=userId 按 create_time 倒序分页
     * （每页 SystemConstants.MAX_PAGE_SIZE 条），逐条 join 游记表取标题与 images 首图，
     * 游记已删除的评论跳过；每条返回 Map{id, noteId, noteTitle, noteImage, content, createTime}，
     * total=该用户评论总数（详见 UserServiceImpl.queryUserComments）。
     * @param userId  被查看评论的用户id
     * @param current 页码，默认第1页
     * @return data=List<Map>，total=评论总数
     */
    Result queryUserComments(Long userId, Integer current);

    /**
     * 【赞过列表的实现】某用户赞过的游记列表（分页，主页"赞过" tab 游记子页）
     * <p>
     * 实现细节：遍历 Redis key note:liked:*（KEYS 前缀扫描，demo 规模游记数有限可行），
     * 对每个赞过集合 ZSet 用 score(key, userId) 判当前用户是否点赞（score 非空即赞过），
     * score=点赞时间戳；收集后按时间戳倒序手动分页（每页 SystemConstants.MAX_PAGE_SIZE 条），
     * 逐条查游记返回 List<Map{id, title, image}>（游记已删除跳过）；赞过记录存于各游记的
     * 点赞 ZSet 中，无独立"用户赞过"集合（详见 UserServiceImpl.queryLikedNotes）。
     * @param userId  被查看赞过列表的用户id
     * @param current 页码，默认第1页
     * @return data=List<Map{id, title, image}>，total=赞过游记总数
     */
    Result queryLikedNotes(Long userId, Integer current);

    /**
     * 【赞过列表的实现】某用户赞过的景点列表（分页，主页"赞过" tab 景点子页）
     * <p>
     * 实现细节：与 queryLikedNotes 同理，遍历 Redis key attraction:liked:*（ATTRACTION_LIKED_KEY
     * 前缀扫描），用 score(key, userId) 判当前用户是否点赞；按点赞时间戳倒序分页后逐条查景点，
     * 返回 List<Map{id, title, image}>（景点已删除跳过），详见 UserServiceImpl.queryLikedAttractions。
     * @param userId  被查看赞过列表的用户id
     * @param current 页码，默认第1页
     * @return data=List<Map{id, title, image}>，total=赞过景点总数
     */
    Result queryLikedAttractions(Long userId, Integer current);

    /**
     * 【打卡与足迹的实现】某用户的打卡景点列表（分页，足迹展示——**足迹公开**，他人主页也可查看）
     * <p>
     * 实现细节：归属用户校验（getById 查不到 fail"用户不存在"）；按 tb_attraction_checkin.user_id
     * 等值 + create_time 倒序分页（每页 MAX_PAGE_SIZE），逐条 join tb_attraction 取名称/首图/坐标，
     * 返回 List<Map{id, name, image, x, y, checkinTime}>；景点已删除跳过该条，total=打卡总数。
     * @param userId  被查看足迹的用户id（他人可看：足迹公开）
     * @param current 页码，默认第1页
     * @return data=List<Map{id, name, image, x, y, checkinTime}>，total=打卡总数
     */
    Result queryUserCheckins(Long userId, Integer current);

}
