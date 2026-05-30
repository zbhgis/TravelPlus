package com.travelplus.controller;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.travelplus.dto.LoginFormDTO;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.dto.UserEditDTO;
import com.travelplus.entity.User;
import com.travelplus.entity.UserInfo;
import com.travelplus.service.IBrowseRecordService;
import com.travelplus.service.IUserInfoService;
import com.travelplus.service.IUserService;
import com.travelplus.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;

import static com.travelplus.utils.RedisConstants.LOGIN_USER_KEY;

/**
 * 【用户模块接口的实现】用户模块统一入口：验证码、登录/注册/登出、当前用户、资料详情、签到、浏览记录、用户主页
 * <p>
 * 实现细节：
 * - 路由域：@RequestMapping("/user")，全部用户侧接口挂 /user/** 前缀。
 * - 鉴权：除放行路径（/user/code、/user/login、/user/register、/user/home/**）外，其余端点
 *   （/user/me、/user/sign、/user/info、/user/browse/list 等）均被 LoginInterceptor 拦截，未登录返回 401；
 *   当前登录用户统一从 UserHolder 获取（由 RefreshTokenInterceptor 从 Redis 登录态写入）。
 * - 登录态载体：登录/注册成功返回裸 token，前端存请求头 authorization（小写）透传；
 *   服务端以 Redis login:token:{token} 维护，RefreshTokenInterceptor 每次请求校验并滚动续期。
 * - 统一返回 Result：{success, errorMsg, data, total}，null 字段不输出。
 *
 * @author TravelPlus
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IBrowseRecordService browseRecordService;

    /**
     * 【验证码发送的实现】发送手机验证码（6位随机数字，存 Redis，测试环境打印到后端日志）
     * <p>
     * 实现细节：
     * - 匿名可访问：/user/code 已在 MvcConfig 登录拦截器 exclude 列表放行，未登录也可调用。
     * - 入参 phone 先经 RegexUtils.isPhoneInvalid 校验，格式非法直接 fail "手机号格式错误！"。
     * - 验证码 RandomUtil.randomNumbers(6) 生成，写 Redis key login:code:{phone}（LOGIN_CODE_KEY），
     *   设 LOGIN_CODE_TTL 分钟有效期；测试环境不真正发短信，log.debug 打印到后端日志，
     *   也可用 docker exec company-agent-redis redis-cli GET login:code:{phone} 直接读取。
     * @param phone   手机号
     * @param session 会话（预留参数，验证码实际以 Redis 为准）
     * @return 发送成功返回 ok
     */
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone, HttpSession session) {
        // 发送短信验证码并保存验证码
        return userService.sendCode(phone, session);
    }

    /**
     * 【登录接口的实现】登录：手机号+密码 或 手机号+验证码，成功返回 token
     * <p>
     * 实现细节：
     * - 匿名可访问：/user/login 在 MvcConfig 登录拦截器 exclude 列表放行。
     * - 双分支设计：password 非空走密码登录（未注册 fail"该手机号未注册，请先注册"；BCrypt.checkpw 校验，
     *   失败统一 fail"手机号或密码错误"，不泄露手机号是否已注册/已设密码）；password 为空回退验证码登录
     *   （Redis 校验 login:code:{phone}，失败 fail"验证码错误"；用户不存在时 createUserWithPhone 自动建号）。
     * - 两条分支收敛到 saveLoginToken(user)：UserDTO（id/nickName/icon）写入 Redis Hash
     *   login:token:{token} 并设 TTL，返回裸 token 字符串，前端放入 authorization 请求头。
     * @param loginForm 登录参数：{phone, code?}（验证码登录）或 {phone, password}（密码登录）
     * @param session   会话（预留参数）
     * @return data=token
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm, HttpSession session){
        // 实现登录功能
        return userService.login(loginForm, session);
    }

    /**
     * 【密码登录与注册的实现】用户注册
     * <p>
     * 实现细节：
     * - 接口用途：手机号+密码（≥6位）+可选昵称注册新账号，已注册手机号会 fail 拒绝重复注册。
     * - 注册成功自动登录：服务端直接复用 saveLoginToken 生成登录态并返回 token，
     *   前端拿到 token 即处于已登录状态，无需再调 /user/login。
     * - 匿名可访问：/user/register 已在 MvcConfig 登录拦截器 exclude 列表中放行，未登录也可调用。
     * @param loginForm 注册参数，包含手机号、密码、昵称
     * @return token
     */
    @PostMapping("/register")
    public Result register(@RequestBody LoginFormDTO loginForm){
        // 实现注册功能，注册成功自动登录
        return userService.register(loginForm);
    }

    /**
     * 【登出功能的实现】登出接口：删除服务端 Redis 登录态，前端随后清理本地 token
     * <p>
     * 实现细节：
     * - 从请求头 authorization 中取出 token（登录时返回的令牌，RefreshTokenInterceptor 也用它做续期与鉴权）。
     * - 非空则删除 Redis key login:token:{token}（LOGIN_USER_KEY），服务端登录态即刻失效；
     *   之后携带该 token 的请求会被 RefreshTokenInterceptor 判定无效而不再写入 UserHolder。
     * - 前后端配合：服务端删登录态 + 前端随后清理本地 token，双端同步失效；
     *   即便前端本地清理失败，服务端也已拒绝该 token，不会留下"假登录"。
     * - token 为空或已过期时删除是幂等操作，直接返回 ok，不报错。
     * @return 无
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request){
        // 1.从请求头取出token
        String token = request.getHeader("authorization");
        // 2.非空则删除Redis登录态（login:token:{token}）
        if (StrUtil.isNotBlank(token)) {
            stringRedisTemplate.delete(LOGIN_USER_KEY + token);
        }
        return Result.ok();
    }

    /**
     * 【当前用户信息查询的实现】查询当前登录用户信息（登录态里缓存的 UserDTO）
     * <p>
     * 实现细节：
     * - 需登录：/user/me 在 LoginInterceptor 拦截范围内，未登录返回 401。
     * - 数据直接取自 UserHolder.getUser()（RefreshTokenInterceptor 从 Redis login:token:{token}
     *   反序列化写入），不查数据库，返回 {id, nickName, icon, bgImage, isFollowed}。
     * @return data=UserDTO
     */
    @GetMapping("/me")
    public Result me(){
        // 获取当前登录的用户并返回
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    /**
     * 【用户详情查询的实现】查询用户个人详情（tb_user_info），未填写时返回空 ok
     * <p>
     * 实现细节：
     * - 按 userId 查 tb_user_info；首次查看（从未编辑过详情）返回 ok 且 data 为 null，前端需判空。
     * - 返回前清空 createTime/updateTime 时间戳字段，避免管理类字段泄露到前端展示层。
     * @param userId 用户id
     * @return data=UserInfo 或 null
     */
    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId){
        // 查询详情
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            // 没有详情，应该是第一次查看详情
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        // 返回
        return Result.ok(info);
    }

    /**
     * 【用户公开信息的实现】按 id 查询用户公开信息（脱敏后仅含 id/nickName/icon）
     * <p>
     * 实现细节：
     * - 查 tb_user 后用 BeanUtil.copyProperties 拷贝为 UserDTO，丢弃 phone/password 等敏感字段，
     *   对外只暴露 id/nickName/icon（/imgs/ 开头的头像路径）。
     * - 用户不存在时返回 ok 且 data=null，前端需判空。
     * @param userId 用户id
     * @return data=UserDTO 或 null
     */
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId){
        // 查询详情
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 返回
        return Result.ok(userDTO);
    }

    /**
     * 【签到功能的实现】当前登录用户今日签到（Redis BitMap 位图）
     * <p>
     * 实现细节：
     * - 需登录：/user/sign 在 LoginInterceptor 拦截范围内，未登录返回 401。
     * - 签到位图 key = sign:{userId}:yyyyMM（USER_SIGN_KEY），SETBIT offset=dayOfMonth-1 置 1
     *   （offset0 对应每月 1 号）；每位占 1 bit，全月约 4 字节，空间开销极小且天然支持连续签到统计。
     * - 重复签到为幂等写入（同一 bit 重复置 1），不报错；连续签到天数统计见 /user/sign/count。
     * @return 签到成功返回 ok
     */
    @PostMapping("/sign")
    public Result sign(){
        return userService.sign();
    }

    /**
     * 【签到统计的实现】统计当前登录用户本月连续签到天数（从今天往前连续）
     * <p>
     * 实现细节：
     * - 读取与 sign() 同 key（sign:{userId}:yyyyMM）的位图，BITFIELD GET u{dayOfMonth} 0 取本月 1 号至今天。
     * - 位序约定（Redis 实测）：返回整数的 LSB = 今天（dayOfMonth 对应位），从 LSB 右移逐天往前
     *   数连续签到天数，遇 0 停止；无签到记录或结果为 0 时直接返回 0。
     * @return data=连续签到天数 int
     */
    @GetMapping("/sign/count")
    public Result signCount(){
        return userService.signCount();
    }

    /**
     * 【用户主页的实现】用户主页聚合接口（公开）：基础信息 + 实时社交统计 + 登录关注态
     * <p>
     * 实现细节：
     * - 主页公开：/user/home/** 已在 MvcConfig 登录拦截器 exclude 列表中放行，未登录也可访问；
     *   service 内部对 UserHolder 判空，未登录时 followed 恒为 false。
     * - data 结构：{user: UserDTO, info: UserInfo|null, fans, followee, likedCount, followed, noteCount}，
     *   统计值均为实时 COUNT/SUM，见 service 实现注释。
     * @param id 被查看主页的用户id
     */
    @GetMapping("/home/{id}")
    public Result queryUserHome(@PathVariable("id") Long id){
        return userService.queryUserHome(id);
    }

    /**
     * 【签到功能的实现】查询当前登录用户本月的签到日期数组
     * <p>
     * 实现细节：
     * - 需登录：/user/** 除放行路径外均被 LoginInterceptor 拦截，未登录访问返回 401。
     * - 从与 sign()/signCount() 相同的 BitMap key（sign:{userId}:yyyyMM）读取本月截止今天的所有位，
     *   解析出已签到的日期号数组（如 [1,5,8]）返回，见 service 实现注释。
     */
    @GetMapping("/sign/list")
    public Result signList(){
        return userService.signList();
    }

    /**
     * 【用户主页编辑的实现】编辑个人资料/头像/背景图/隐私设置（仅限本人）
     * <p>
     * 实现细节：
     * - 需登录：PUT /user/info 在 LoginInterceptor 拦截范围内（/user/** 除放行路径），未登录返回 401。
     * - body 接收 UserEditDTO（nickName/icon/bgImage/introduce/city/gender/birthday/followVisible/fansVisible），
     *   字段可部分提交，service 只更新非空字段，见 UserServiceImpl.updateUserInfo 注释。
     * - 服务端只认 UserHolder 当前登录用户，dto 不含 userId，天然只能改自己。
     * - 编辑成功后同步刷新 Redis 登录态：登录态 Hash 里缓存了 UserDTO 的 nickName/icon，
     *   不刷新则 /user/me 直到重新登录都显示旧昵称/头像。这里用请求头 authorization 里的 token
     *   找到当前登录态 key（login:token:{token}），只把本次变更的 nickName/icon 写回 Hash；
     *   其余字段（bgImage 等）不在登录态里，无需同步。
     * @param dto 编辑入参（字段均可选，非空才更新）
     */
    @PutMapping("/info")
    public Result updateUserInfo(@RequestBody UserEditDTO dto, HttpServletRequest request){
        Result result = userService.updateUserInfo(dto);
        if (result.getSuccess()) {
            // 同步刷新 Redis 登录态中的昵称/头像（与 saveLoginToken 写入的 Hash 字段名一致：nickName/icon）
            String token = request.getHeader("authorization");
            if (StrUtil.isNotBlank(token)) {
                Map<String, Object> userMap = new HashMap<>();
                if (StrUtil.isNotBlank(dto.getNickName())) {
                    userMap.put("nickName", dto.getNickName());
                }
                if (StrUtil.isNotBlank(dto.getIcon())) {
                    userMap.put("icon", dto.getIcon());
                }
                if (!userMap.isEmpty()) {
                    stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
                }
            }
        }
        return result;
    }

    /**
     * 【浏览记录的实现】查询当前登录用户最近浏览的游记/景点（Redis ZSet，按浏览时间倒序）
     * <p>
     * 实现细节：
     * - 需登录：GET /user/browse/list 在 LoginInterceptor 拦截范围内，未登录返回 401。
     * - 数据来源为 Redis key browse:{userId}，返回最近 50 条 List<Map>：{type, id, title, image, time}，
     *   组装逻辑见 BrowseRecordServiceImpl.list 注释。
     */
    @GetMapping("/browse/list")
    public Result browseList(){
        // 当前登录用户（拦截器已保证非空）
        UserDTO user = UserHolder.getUser();
        return browseRecordService.list(user.getId());
    }
}