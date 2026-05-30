package com.travelplus.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.LoginFormDTO;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.dto.UserEditDTO;
import com.travelplus.entity.Follow;
import com.travelplus.entity.TravelNote;
import com.travelplus.entity.User;
import com.travelplus.entity.UserInfo;
import com.travelplus.mapper.FollowMapper;
import com.travelplus.mapper.TravelNoteMapper;
import com.travelplus.mapper.UserMapper;
import com.travelplus.service.IUserInfoService;
import com.travelplus.service.IUserService;
import com.travelplus.utils.RegexUtils;
import com.travelplus.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.travelplus.utils.RedisConstants.*;
import static com.travelplus.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * 【用户服务的实现】用户服务实现：验证码/密码登录、注册、签到、主页聚合与资料编辑
 * <p>
 * 实现细节：
 * - 登录态：saveLoginToken 统一落 Redis Hash（key=login:token:{token}），密码登录/验证码登录/注册三处共用，
 *   由 RefreshTokenInterceptor 续期、logout 删除。
 * - 签到：sign/signCount/signList 基于 Redis BitMap（key=sign:{userId}:yyyyMM），位序约定见各方法注释。
 * - 主页聚合：实时 COUNT/SUM 统计社交数据（避免读 UserInfo 冗余字段），关注态由 UserHolder 判空决定。
 * - 资料编辑：仅限本人（UserHolder 取当前用户，dto 不含 userId），tb_user/tb_user_info 非空字段局部更新。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private FollowMapper followMapper;

    @Resource
    private TravelNoteMapper noteMapper;

    /**
     * 【验证码登录的实现】发送登录验证码
     * <p>
     * 实现细节：
     * - 手机号格式校验（RegexUtils.isPhoneInvalid）不通过直接 fail "手机号格式错误！"，不落库。
     * - 通过后 RandomUtil.randomNumbers(6) 生成 6 位数字验证码，写入 Redis
     *   key=login:code:{phone}，TTL=LOGIN_CODE_TTL 分钟（过期自动失效，防验证码长期有效）。
     * - 验证码仅打 debug 日志（演示环境不接短信通道），前端登录时回填 code 与之比对。
     * @param phone   手机号
     * @param session 会话（兼容保留，验证码实际存 Redis）
     * @return 发送成功返回 ok
     */
    @Override
    public Result sendCode(String phone, HttpSession session) {
        // 1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.符合，生成验证码
        String code = RandomUtil.randomNumbers(6);

        // 4.保存验证码到 session
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        // 5.发送验证码
        log.debug("发送短信验证码成功，验证码：{}", code);
        // 返回ok
        return Result.ok();
    }

    /**
     * 【密码登录与注册的实现】登录接口：password 非空走密码登录，否则回退验证码登录
     * <p>
     * 实现细节：
     * - 双分支设计：以 StrUtil.isNotBlank(password) 判断分支——password 非空优先走密码登录；
     *   password 为空时回退原验证码登录，既支持新客户端"手机号+密码"登录，又兼容老客户端"手机号+验证码"登录。
     * - 密码分支链路：手机号格式校验 → 按 phone 查用户（未注册 fail "该手机号未注册，请先注册"）→
     *   BCrypt.checkpw 校验密码 → saveLoginToken 落登录态返回 token。
     * - 校验失败（user.getPassword() 为空 或 BCrypt.checkpw 不匹配）统一 fail "手机号或密码错误"，
     *   不区分"未设置密码"与"密码错误"，避免向调用方泄露手机号是否已注册/已设密码的信息。
     * - 验证码分支链路：Redis 取 login:code:{phone} 校验 → 查用户（不存在时 createUserWithPhone 自动建号）→
     *   saveLoginToken 返回 token。
     * - 两条分支最终都收敛到 saveLoginToken(user)，登录态统一存 Redis Hash，登录方式不影响后续鉴权。
     */
    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.密码登录分支：password 非空时优先走密码校验
        String password = loginForm.getPassword();
        if (StrUtil.isNotBlank(password)) {
            // 3.1.根据手机号查询用户
            User user = query().eq("phone", phone).one();
            // 3.2.判断用户是否存在
            if (user == null) {
                // 不存在，提示先注册
                return Result.fail("该手机号未注册，请先注册");
            }
            // 3.3.校验密码（BCrypt）
            if (StrUtil.isBlank(user.getPassword()) || !BCrypt.checkpw(password, user.getPassword())) {
                // 密码不一致，报错
                return Result.fail("手机号或密码错误");
            }
            // 3.4.保存用户信息到 redis 并返回token
            return Result.ok(saveLoginToken(user));
        }
        // 4.验证码登录：从redis获取验证码并校验
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
            // 不一致，报错
            return Result.fail("验证码错误");
        }

        // 5.一致，根据手机号查询用户 select * from tb_user where phone = ?
        User user = query().eq("phone", phone).one();

        // 6.判断用户是否存在
        if (user == null) {
            // 7.不存在，创建新用户并保存
            user = createUserWithPhone(phone);
        }

        // 8.保存用户信息到 redis 并返回token
        return Result.ok(saveLoginToken(user));
    }

    /**
     * 【密码登录与注册的实现】注册接口：手机号+密码（≥6位）+可选昵称创建账号，注册成功自动登录
     * <p>
     * 实现细节：
     * - 校验链：手机号格式（RegexUtils.isPhoneInvalid）→ 密码非空且 ≥6 位 → 手机号查重（已存在 fail "该手机号已注册"），
     *   任一步不通过直接 fail，不落库，保证账号唯一性与数据质量。
     * - 密码 BCrypt.hashpw 加密存储：数据库只存哈希不存明文，与登录的 BCrypt.checkpw 校验配套；默认头像 icon 置空串。
     * - 昵称可选：nickName 为空时用 USER_NICK_NAME_PREFIX + RandomUtil.randomString(10) 自动生成，避免出现空昵称。
     * - 注册成功自动登录：直接复用 saveLoginToken(user) 生成登录态并返回 token，
     *   前端注册完无需再跳登录页，一次请求即完成"注册+登录"的体验。
     */
    @Override
    public Result register(LoginFormDTO loginForm) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }
        // 3.校验密码：非空且不少于6位
        String password = loginForm.getPassword();
        if (StrUtil.isBlank(password) || password.length() < 6) {
            return Result.fail("密码长度不能少于6位");
        }
        // 4.查询手机号是否已注册
        User exist = query().eq("phone", phone).one();
        if (exist != null) {
            return Result.fail("该手机号已注册");
        }
        // 5.创建新用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(StrUtil.isBlank(loginForm.getNickName())
                ? USER_NICK_NAME_PREFIX + RandomUtil.randomString(10)
                : loginForm.getNickName());
        // 6.密码 BCrypt 加密存储
        user.setPassword(BCrypt.hashpw(password));
        // 【注册默认头像的实现】icon 默认指向系统预置默认头像 /imgs/user/avatar/default.png，
        // 新注册用户展示统一默认头像，上传/编辑头像后覆盖（User 实体字段默认值已同步）。
        user.setIcon("/imgs/user/avatar/default.png");
        // 7.保存用户
        save(user);
        // 8.注册成功自动登录，返回token
        return Result.ok(saveLoginToken(user));
    }

    /**
     * 【密码登录与注册的实现】将登录用户写入 Redis（key: login:token:{token}），返回登录令牌
     * <p>
     * 实现细节：
     * - token 用 UUID.randomUUID().toString(true)（hutool，去掉横线）生成，随机性足够且无特殊字符，可直接拼进 Redis key。
     * - User → UserDTO → Map 后写入 Redis Hash：BeanUtil.copyProperties 只复制 UserDTO 需要的字段（id/nickName/icon），
     *   避免把 phone/password 等敏感字段带进登录态；beanToMap 配置 setIgnoreNullValue(true)（null 字段不写入）
     *   与 setFieldValueEditor(...toString())（值统一转字符串，Redis Hash 只支持字符串值）。
     * - key 约定为 LOGIN_USER_KEY + token（login:token:{token}），与 RefreshTokenInterceptor 续期校验、logout 删除共用，
     *   保证登录态生命周期在同一处维护。
     * - TTL 首次写入时设 LOGIN_USER_TTL（分钟）；之后每次请求经 RefreshTokenInterceptor 校验通过会重新 expire 续期，
     *   登录态随活跃请求滚动续期，长期不活跃才过期。
     * - 返回裸 token 字符串，前端放入 authorization 请求头；密码登录/验证码登录/注册三处调用共用本方法。
     * @param user 登录用户
     * @return token
     */
    private String saveLoginToken(User user) {
        // 1.随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString(true);
        // 2.将User对象转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        // 判空兜底：hutool 的 fieldValueEditor 会先于 ignoreNullValue 过滤被调用，
                        // 若 DTO 存在 null 字段（如未来再新增无默认值的可空字段）直接 toString() 会抛 NPE，
                        // 这里把 null 归一为 ""，保证登录/注册/验证码登录链路永不因可空字段崩溃
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue == null ? "" : fieldValue.toString()));
        // 3.存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        // 4.设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.MINUTES);
        // 5.返回token
        return token;
    }

    /**
     * 【签到功能的实现】当天签到（SETBIT 写入本月签到位图）
     * <p>
     * 实现细节：
     * - key=sign:{userId}:yyyyMM（USER_SIGN_KEY + userId + ":" + 年月），yyyyMM 决定"本月"归属，
     *   跨月自动使用新 key，无需清理历史数据。
     * - 当天位 = dayOfMonth-1（1号→bit0），SETBIT 置 1；重复签到是对同一位再次写 1，天然幂等。
     * - 位图数据被 signCount/signList 用 BITFIELD 批量读取，三方法读写同一 key。
     */
    @Override
    public Result sign() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.写入Redis SETBIT key offset 1
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 【签到功能的实现】统计当前登录用户本月"连续签到"天数（从今天往前连续签到）
     * <p>
     * 实现细节：
     * - 位序（Redis 实测）：BITFIELD GET u{dayOfMonth} 0 把 Redis 的 offset0 作为结果整数的最高位（MSB），
     *   因此结果整数的最低位（LSB）= Redis bit(dayOfMonth-1) = 今天，次低位 = 昨天……
     *   即 num 的第 i 位（从 LSB 数）对应日期 dayOfMonth - i。
     * - 连续签到语义：从 LSB（今天）开始右移逐天往昨天数连续签到的天数，遇到未签到位即停止——
     *   当前数据（sign:1:202608，5、6号签到，dayOfMonth=6）：GET u6 0 = 3（0b000011），
     *   LSB=1（6号）、次低位=1（5号）→ 连续 2 天，符合预期。
     * - key 与 sign()/signList() 完全一致：USER_SIGN_KEY + userId + ":" + yyyyMM，读写同一份位图。
     * - 无签到记录（result 空或 num 为 0）直接返回 0。
     */
    @Override
    public Result signCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.获取今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.获取本月截止今天为止的所有的签到记录 BITFIELD GET u{dayOfMonth} 0
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty() || result.get(0) == null) {
            // 没有任何签到结果
            return Result.ok(0);
        }
        Long num = result.get(0);
        if (num == 0) {
            return Result.ok(0);
        }
        // 6.从 LSB（今天）右移逐天往前数连续签到：LSB=今天，遇 0 停止
        int count = 0;
        while (true) {
            // 6.1.让这个数字与1做与运算，得到数字最低位bit位，判断今天是否签到
            if ((num & 1) == 0) {
                // 如果为0，说明今天未签到（或连续中断），结束
                break;
            } else {
                // 如果不为0，说明已签到，连续天数+1
                count++;
            }
            // 把数字右移一位，抛弃最低位bit位，继续往前一天
            num >>>= 1;
        }
        return Result.ok(count);
    }

    /**
     * 【用户主页的实现】聚合用户主页数据：基础信息 + 实时社交统计 + 登录用户关注态
     * <p>
     * 实现细节：
     * - 数据来源：fans/followee/noteCount 用 COUNT(*)、likedCount 用 SUM(liked) 实时统计，
     *   不读取 tb_user_info.fans/followee 冗余字段——冗余字段在关注/取关、发游记时并不同步更新，
     *   实时统计能保证主页数字与关注列表、游记列表始终一致。
     * - fans = COUNT(tb_follow.follow_user_id = id)：谁关注了我；followee = COUNT(tb_follow.user_id = id)：我关注了谁。
     * - likedCount = SUM(tb_travel_note.liked WHERE user_id = id)，COALESCE 保证无游记时返回 0 而非 null。
     * - followed 判定：仅当请求携带登录态（UserHolder 非空）时，才查当前登录用户是否关注了 id；
     *   未登录访问主页时 followed 恒为 false——主页是公开页面，不做登录强校验。
     * - 返回值：Map 结构 {user: UserDTO, info: UserInfo|null, fans, followee, likedCount, followed, noteCount}，
     *   info 可能为 null（用户从未编辑过个人详情），前端需判空展示。
     * - 直接注入 FollowMapper/TravelNoteMapper 统计，而非注入 FollowService/TravelNoteService，
     *   避免与 FollowServiceImpl/TravelNoteServiceImpl（二者都注入 IUserService）形成循环依赖。
     * @param id 被查看主页的用户id
     */
    @Override
    public Result queryUserHome(Long id) {
        // 1.查询用户，不存在直接 fail
        User user = getById(id);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        // 2.查询用户详情（可能为 null，用户未编辑过详情）
        UserInfo info = userInfoService.getById(id);
        if (info != null) {
            // 与 /user/info/{id} 一致：不向客户端暴露 createTime/updateTime
            info.setCreateTime(null);
            info.setUpdateTime(null);
        }
        // 3.实时统计粉丝数：COUNT(*) FROM tb_follow WHERE follow_user_id = id
        Long fans = followMapper.selectCount(new QueryWrapper<Follow>().eq("follow_user_id", id));
        // 4.实时统计关注数：COUNT(*) FROM tb_follow WHERE user_id = id
        Long followee = followMapper.selectCount(new QueryWrapper<Follow>().eq("user_id", id));
        // 5.实时统计获赞数：SELECT COALESCE(SUM(liked),0) FROM tb_travel_note WHERE user_id = id
        TravelNote likedRow = noteMapper.selectOne(new QueryWrapper<TravelNote>()
                .select("COALESCE(SUM(liked), 0) AS liked")
                .eq("user_id", id));
        long likedCount = (likedRow == null || likedRow.getLiked() == null) ? 0 : likedRow.getLiked();
        // 6.实时统计游记数：COUNT(*) FROM tb_travel_note WHERE user_id = id
        Long noteCount = noteMapper.selectCount(new QueryWrapper<TravelNote>().eq("user_id", id));
        // 7.判定当前登录用户是否已关注 id（未登录则 false）
        boolean followed = false;
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser != null) {
            Long followCount = followMapper.selectCount(new QueryWrapper<Follow>()
                    .eq("user_id", currentUser.getId())
                    .eq("follow_user_id", id));
            followed = followCount != null && followCount > 0;
        }
        // 8.封装返回
        Map<String, Object> data = new HashMap<>();
        data.put("user", userDTO);
        data.put("info", info);
        data.put("fans", fans);
        data.put("followee", followee);
        data.put("likedCount", likedCount);
        data.put("followed", followed);
        data.put("noteCount", noteCount);
        return Result.ok(data);
    }

    /**
     * 【签到功能的实现】获取当前登录用户本月的签到日期数组
     * <p>
     * 实现细节：
     * - key 与 sign()/signCount() 完全一致：USER_SIGN_KEY + userId + ":" + yyyyMM（如 sign:1:202608），
     *   保证写（sign 的 SETBIT）读（signList/signCount 的 BITFIELD）操作同一份位图，不产生数据分裂。
     * - 位序（Redis 实测）：BITFIELD GET u{dayOfMonth} 0 把 Redis 的 offset0 作为结果整数的最高位（MSB），
     *   因此结果整数的最低位（LSB）= Redis bit(dayOfMonth-1) = 今天；num 的第 i 位（从 LSB 数）对应日期 dayOfMonth - i。
     * - 解析：遍历 i=0..dayOfMonth-1，第 i 位为 1 表示日期 dayOfMonth-i 已签到，加入结果数组；
     *   由于 i=0 是今天（最大日期）、i 越大日期越小，收集到的是降序日期，
     *   最后 sort 升序返回 [5,6] 这类"已签到日期号"列表，由前端渲染日历。
     * - 只统计到今天（dayOfMonth）为止，未来日期不存在对应位，无需特殊处理。
     */
    @Override
    public Result signList() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.获取日期
        LocalDateTime now = LocalDateTime.now();
        // 3.拼接key（与 sign/signCount 一致）
        String keySuffix = now.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.今天是本月的第几天
        int dayOfMonth = now.getDayOfMonth();
        // 5.取本月截止今天的所有签到位
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        List<Integer> signDays = new ArrayList<>();
        if (result == null || result.isEmpty() || result.get(0) == null) {
            // 无签到记录
            return Result.ok(signDays);
        }
        Long num = result.get(0);
        if (num == 0) {
            return Result.ok(signDays);
        }
        // 6.逐位解析：num 第 i 位（LSB 起）=1 表示日期 dayOfMonth-i 已签到（i=0 为今天）
        for (int i = 0; i < dayOfMonth; i++) {
            if ((num & (1L << i)) != 0) {
                signDays.add(dayOfMonth - i);
            }
        }
        // 7.i=0 是今天（最大日期）、i 越大日期越小，收集为降序，升序排序后返回
        signDays.sort(Integer::compareTo);
        return Result.ok(signDays);
    }

    private User createUserWithPhone(String phone) {
        // 1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 【注册默认头像的实现】icon 不显式赋值，走 User 实体默认值 /imgs/user/avatar/default.png，
        // 保证通过该入口创建的随机昵称用户同样带默认头像。
        // 2.保存用户
        save(user);
        return user;
    }

    /**
     * 【用户主页编辑的实现】编辑个人资料/头像/背景图/隐私设置（仅限本人，登录拦截器已保证登录态）
     * <p>
     * 实现细节：
     * - 只允许改自己：以 UserHolder 当前登录用户为准，dto 不接收 userId，杜绝越权修改他人资料。
     * - tb_user 更新：nickName/icon/bgImage 非空才更新（前端"部分保存"时未提交字段不会被覆盖），
     *   任一字段被更新时同步写 updateTime；nickName/icon 变更后的 Redis 登录态同步由 Controller 层
     *   用请求头 token 更新（见 UserController.updateUserInfo），保证 /user/me 即时生效。
     * - tb_user_info 更新：user_info 不存在则先创建（userId=当前用户，粉丝/关注/积分/级别与隐私开关
     *   均写与建表默认一致的初始值，createTime 显式写入），存在则非空字段覆盖；
     *   updateTime 每次写入。updateById 默认"仅更新非 null 字段"策略，天然支持局部更新。
     * - 校验：nickName 非空时长度 ≤20（DB 列宽 32，前端限制更短更友好）；gender 仅 0/1；
     *   隐私开关 followVisible/fansVisible 仅 0/1，越界直接 fail 不入库。
     * - 隐私开关存 tb_user_info，语义：只影响关注/粉丝【列表】的可见性，主页粉丝/关注【计数】仍展示
     *   （小红书风格：数量可见、列表按隐私），校验逻辑见 FollowServiceImpl.queryFollowList。
     * - gender 入 tb_user_info 时由 Integer(0/1) 转 Boolean：1→true(女)，0→false(男)。
     */
    @Override
    public Result updateUserInfo(UserEditDTO dto) {
        // 1.获取当前登录用户（拦截器已保证登录态；只允许改自己）
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        Long userId = currentUser.getId();
        // 2.参数校验
        if (StrUtil.isNotBlank(dto.getNickName()) && dto.getNickName().length() > 20) {
            return Result.fail("昵称长度不能超过20个字符");
        }
        if (dto.getGender() != null && dto.getGender() != 0 && dto.getGender() != 1) {
            return Result.fail("性别只能为0或1");
        }
        if (dto.getFollowVisible() != null && dto.getFollowVisible() != 0 && dto.getFollowVisible() != 1) {
            return Result.fail("隐私设置取值只能为0或1");
        }
        if (dto.getFansVisible() != null && dto.getFansVisible() != 0 && dto.getFansVisible() != 1) {
            return Result.fail("隐私设置取值只能为0或1");
        }
        // 3.更新 tb_user：nickName/icon/bgImage 非空才更新
        User user = new User();
        user.setId(userId);
        boolean updateUser = false;
        if (StrUtil.isNotBlank(dto.getNickName())) {
            user.setNickName(dto.getNickName());
            updateUser = true;
        }
        if (StrUtil.isNotBlank(dto.getIcon())) {
            user.setIcon(dto.getIcon());
            updateUser = true;
        }
        if (StrUtil.isNotBlank(dto.getBgImage())) {
            user.setBgImage(dto.getBgImage());
            updateUser = true;
        }
        if (updateUser) {
            user.setUpdateTime(LocalDateTime.now());
            updateById(user);
        }
        // 4.更新 tb_user_info：不存在则创建（userId=当前用户，初始字段与建表默认一致）
        UserInfo info = userInfoService.getById(userId);
        boolean create = (info == null);
        if (create) {
            info = new UserInfo();
            info.setUserId(userId);
            info.setFans(0);
            info.setFollowee(0);
            info.setCredits(0);
            info.setLevel(false);
            info.setFollowVisible(1);
            info.setFansVisible(1);
            info.setCreateTime(LocalDateTime.now());
        }
        // 5.非空字段覆盖（updateById 默认策略：仅更新非 null 字段，null 字段保留原值）
        if (StrUtil.isNotBlank(dto.getIntroduce())) {
            info.setIntroduce(dto.getIntroduce());
        }
        if (StrUtil.isNotBlank(dto.getCity())) {
            info.setCity(dto.getCity());
        }
        if (dto.getGender() != null) {
            info.setGender(dto.getGender() == 1);
        }
        if (dto.getBirthday() != null) {
            info.setBirthday(dto.getBirthday());
        }
        if (dto.getFollowVisible() != null) {
            info.setFollowVisible(dto.getFollowVisible());
        }
        if (dto.getFansVisible() != null) {
            info.setFansVisible(dto.getFansVisible());
        }
        info.setUpdateTime(LocalDateTime.now());
        if (create) {
            userInfoService.save(info);
        } else {
            userInfoService.updateById(info);
        }
        // 6.返回
        return Result.ok();
    }
}
