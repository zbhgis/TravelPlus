package com.travelplus.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.LoginFormDTO;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.dto.UserEditDTO;
import com.travelplus.entity.Attraction;
import com.travelplus.entity.AttractionCheckin;
import com.travelplus.entity.BalanceRecord;
import com.travelplus.entity.Follow;
import com.travelplus.entity.PointsRecord;
import com.travelplus.entity.TicketOrder;
import com.travelplus.entity.TravelNote;
import com.travelplus.entity.TravelNoteComment;
import com.travelplus.entity.User;
import com.travelplus.entity.UserInfo;
import com.travelplus.mapper.AttractionCheckinMapper;
import com.travelplus.mapper.AttractionMapper;
import com.travelplus.mapper.BalanceRecordMapper;
import com.travelplus.mapper.FollowMapper;
import com.travelplus.mapper.PointsRecordMapper;
import com.travelplus.mapper.TicketOrderMapper;
import com.travelplus.mapper.TravelNoteCommentMapper;
import com.travelplus.mapper.TravelNoteMapper;
import com.travelplus.mapper.UserMapper;
import com.travelplus.service.IUserInfoService;
import com.travelplus.service.IUserService;
import com.travelplus.utils.RegexUtils;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

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

    @Resource
    private TravelNoteCommentMapper commentMapper;

    @Resource
    private AttractionMapper attractionMapper;

    /** 【打卡与足迹的实现】打卡表 Mapper（足迹列表：按用户查打卡记录并 join 景点） */
    @Resource
    private AttractionCheckinMapper checkinMapper;

    /** 【签到积分与钱包的实现】积分流水表 Mapper（签到 +2 落流水、积分记录分页列表） */
    @Resource
    private PointsRecordMapper pointsRecordMapper;

    /** 【钱包资产的实现】秒杀订单表 Mapper（钱包页统计已支付/已核销的秒杀券数量） */
    @Resource
    private TicketOrderMapper ticketOrderMapper;

    /** 【余额流水的实现】余额流水表 Mapper（模拟充值 +余额落流水、余额明细分页列表） */
    @Resource
    private BalanceRecordMapper balanceRecordMapper;

    /** 【积分记录的实现】积分变动类型 → 展示文案映射（sign=签到奖励 order=下单抵扣 refund=退款返还 admin=运营调整） */
    private static final Map<String, String> POINTS_TYPE_TEXT = new LinkedHashMap<>();

    static {
        POINTS_TYPE_TEXT.put("sign", "签到奖励");
        POINTS_TYPE_TEXT.put("order", "下单抵扣");
        POINTS_TYPE_TEXT.put("refund", "退款返还");
        POINTS_TYPE_TEXT.put("admin", "运营调整");
    }

    /** 【余额流水的实现】余额变动类型 → 展示文案映射（recharge=充值 pay=消费 refund=退款 admin=调整） */
    private static final Map<String, String> BALANCE_TYPE_TEXT = new LinkedHashMap<>();

    static {
        BALANCE_TYPE_TEXT.put("recharge", "充值");
        BALANCE_TYPE_TEXT.put("pay", "消费");
        BALANCE_TYPE_TEXT.put("refund", "退款");
        BALANCE_TYPE_TEXT.put("admin", "调整");
    }

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
     * 【签到积分的实现】当天签到 = 签到位图置 1 + 积分 +2（SQL 原子自增） + 积分流水落库
     * <p>
     * 实现细节：
     * - key=sign:{userId}:yyyyMM（USER_SIGN_KEY + userId + ":" + 年月），yyyyMM 决定"本月"归属，
     *   跨月自动使用新 key，无需清理历史数据。
     * - 当天位 = dayOfMonth-1（1号→bit0）。签到前先 GETBIT 检查今天是否已签：
     *   已签直接 fail"今天已签到，明天再来吧"（修复原幂等 SETBIT 的重复签到 bug——原实现重复
     *   签到静默成功，前端"明明签到过还显示可签到"）；未签才 SETBIT 置 1。
     * - 加积分用 SQL 原子自增 UPDATE tb_user SET points = points + 2 WHERE id=我，
     *   避免读-改-写并发覆盖；积分流水 INSERT tb_points_record(change_points=+2, type='sign',
     *   description='每日签到奖励')。写入顺序：先 SETBIT 成功再记流水；若流水失败（try/catch 记日志）
     *   则积分已加但无流水记录——可接受的最终一致性缺口，不阻断签到主流程。
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
        // 5.签到前先 GETBIT 检查今天是否已签（修复重复签到 bug：重复签到不再静默成功）
        Boolean signed = stringRedisTemplate.opsForValue().getBit(key, dayOfMonth - 1);
        if (Boolean.TRUE.equals(signed)) {
            return Result.fail("今天已签到，明天再来吧");
        }
        // 6.写入Redis SETBIT key offset 1（仅未签时置位）
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        // 7.签到加积分：SQL 原子自增 points=points+2，避免并发读改写覆盖（见方法注释）
        update().setSql("points = points + 2").eq("id", userId).update();
        // 8.记录积分流水（sign=签到 +2）；流水写入失败只记日志，不阻断签到结果
        try {
            PointsRecord record = new PointsRecord();
            record.setUserId(userId);
            record.setChangePoints(2);
            record.setType("sign");
            record.setDescription("每日签到奖励");
            pointsRecordMapper.insert(record);
        } catch (Exception e) {
            log.error("签到积分流水写入失败, userId={}", userId, e);
        }
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
     * 【签到功能的实现】获取当前登录用户指定月份的签到日期数组（缺省=本月）
     * <p>
     * 实现细节：
     * - key 与 sign()/signCount() 完全一致：USER_SIGN_KEY + userId + ":" + yyyyMM（如 sign:1:202608），
     *   保证写（sign 的 SETBIT）读（signList/signCount 的 BITFIELD）操作同一份位图，不产生数据分裂。
     * - month 参数（可选，格式 yyyyMM，如 202610）：供前端日历翻看历史月份；位图无 TTL，历史月份数据长期可查。
     *   缺省/非法格式回退本月；未来月份直接返回空数组（无数据可签）。
     * - 位序（Redis 实测）：BITFIELD GET u{daysInMonth} 0 把 Redis 的 offset0 作为结果整数的最高位（MSB），
     *   因此结果整数的最低位（LSB）= Redis bit(dayOfMonth-1) = 该月最后读取位；num 的第 i 位（从 LSB 数）
     *   对应日期 daysInMonth - i。本月时未来日期位天然为 0，不会误报；历史月全月位均有效。
     * - 解析：遍历 i=0..daysInMonth-1，第 i 位为 1 表示日期 daysInMonth-i 已签到，加入结果数组；
     *   最后 sort 升序返回 [5,6] 这类"已签到日期号"列表，由前端渲染日历。
     */
    @Override
    public Result signList(String month) {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.确定目标月份：month 缺省/非法 → 本月；合法 yyyyMM → 指定月；未来月份 → 返回空
        YearMonth target;
        YearMonth current = YearMonth.from(LocalDateTime.now());
        if (StrUtil.isBlank(month)) {
            target = current;
        } else {
            try {
                target = YearMonth.parse(month, DateTimeFormatter.ofPattern("yyyyMM"));
            } catch (Exception e) {
                target = current;
            }
        }
        if (target.isAfter(current)) {
            return Result.ok(new ArrayList<>());
        }
        // 3.拼接key（与 sign/signCount 一致）
        String keySuffix = target.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String key = USER_SIGN_KEY + userId + keySuffix;
        // 4.目标月天数（本月实际只写到今天，但未来位天然为 0，统一读整月）
        int daysInMonth = target.lengthOfMonth();
        // 5.取该月全部签到位
        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(daysInMonth)).valueAt(0)
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
        // 6.逐位解析：num 第 i 位（LSB 起）=1 表示日期 daysInMonth-i 已签到（i=0 为该月最后一天）
        for (int i = 0; i < daysInMonth; i++) {
            if ((num & (1L << i)) != 0) {
                signDays.add(daysInMonth - i);
            }
        }
        // 7.i=0 是最大日期、i 越大日期越小，收集为降序，升序排序后返回
        signDays.sort(Integer::compareTo);
        return Result.ok(signDays);
    }

    /**
     * 【钱包资产的实现】当前登录用户钱包资产聚合：余额 / 积分 / 秒杀券数量
     * <p>
     * 实现细节：
     * - 需登录：由 UserController.wallet 调用，userId 取 UserHolder（登录拦截器保证非空）。
     * - balance/points：getById(userId) 查 tb_user 直接返回实体字段——balance 单位元（DECIMAL 10,2），
     *   points 为累计积分（签到 +2 由 sign 原子自增）。
     * - couponCount：ticketOrderMapper.selectCount 统计 tb_ticket_order WHERE user_id=当前用户
     *   AND status IN (2,3)（已支付/已核销的秒杀券数量），与订单管理页的状态口径一致。
     * - 返回：data=Map{balance(元), points(积分), couponCount(秒杀券数)}。
     * @return data=Map{balance, points, couponCount}
     */
    @Override
    public Result queryWallet() {
        // 1.当前登录用户（拦截器已保证非空）
        Long userId = UserHolder.getUser().getId();
        User user = getById(userId);
        if (user == null) {
            return Result.fail("用户不存在");
        }
        // 2.秒杀券数 = 已支付(2)/已核销(3) 的订单数
        Long couponCount = ticketOrderMapper.selectCount(
                new QueryWrapper<TicketOrder>()
                        .eq("user_id", userId)
                        .in("status", 2, 3));
        // 3.组装钱包资产
        Map<String, Object> wallet = new LinkedHashMap<>();
        wallet.put("balance", user.getBalance());
        wallet.put("points", user.getPoints());
        wallet.put("couponCount", couponCount);
        return Result.ok(wallet);
    }

    /**
     * 【积分记录的实现】当前登录用户积分增减记录（分页，钱包页"积分明细"）
     * <p>
     * 实现细节：
     * - 需登录：由 UserController.pointsRecords 调用，userId 取 UserHolder（登录拦截器保证非空）。
     * - 分页：pointsRecordMapper.selectPage 查 tb_points_record WHERE user_id=当前用户
     *   按 create_time 倒序，每页 SystemConstants.MAX_PAGE_SIZE 条（MyBatis-Plus 分页插件已配置）。
     * - typeText 文案映射见 POINTS_TYPE_TEXT：sign=签到奖励 order=下单抵扣 refund=退款返还 admin=运营调整；
     *   未知 type（未来扩展）返回 null，前端判空兜底。
     * - 返回：data=List<Map{id, changePoints, type, typeText, description, createTime}>，
     *   total=该用户流水总数（供前端分页）。
     * @param current 页码，默认第1页
     * @return data=积分流水列表，total=流水总数
     */
    @Override
    public Result queryPointsRecords(Integer current) {
        // 1.当前登录用户（拦截器已保证非空）
        Long userId = UserHolder.getUser().getId();
        // 2.按 create_time 倒序分页查该用户积分流水
        Page<PointsRecord> page = pointsRecordMapper.selectPage(
                new Page<>(current, SystemConstants.MAX_PAGE_SIZE),
                new QueryWrapper<PointsRecord>()
                        .eq("user_id", userId)
                        .orderByDesc("create_time"));
        // 3.组装列表项（typeText 文案映射见 POINTS_TYPE_TEXT）
        List<Map<String, Object>> result = new ArrayList<>(page.getRecords().size());
        for (PointsRecord record : page.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", record.getId());
            item.put("changePoints", record.getChangePoints());
            item.put("type", record.getType());
            item.put("typeText", POINTS_TYPE_TEXT.get(record.getType()));
            item.put("description", record.getDescription());
            item.put("createTime", record.getCreateTime());
            result.add(item);
        }
        // 4.返回（total=流水总数，供前端分页）
        return Result.ok(result, page.getTotal());
    }

    /**
     * 【余额流水的实现】当前登录用户余额增减记录（分页，钱包页"余额明细"）
     * <p>
     * 实现细节：
     * - 需登录：由 UserController.walletBalanceRecords 调用，userId 取 UserHolder（登录拦截器保证非空）。
     * - 分页：balanceRecordMapper.selectPage 查 tb_balance_record WHERE user_id=当前用户 按 create_time
     *   倒序（每页 SystemConstants.MAX_PAGE_SIZE 条），与 queryPointsRecords 同构。
     * - typeText 文案映射见 BALANCE_TYPE_TEXT（recharge=充值 pay=消费 refund=退款 admin=调整）；
     *   type 未知时返回 null，前端判空兜底。
     * - 金额口径：changeAmount 直接取 tb_balance_record.change_amount（单位：元，DB 口径，如
     *   充值 100 元存 100.00），正=收入（充值）、负=支出（消费），不做换算。
     * - 返回：data=List<Map{id, changeAmount, type, typeText, payMethod, description, createTime}>，
     *   total=流水总数（供前端分页）。
     * @param current 页码，默认第1页
     * @return data=余额流水列表，total=流水总数
     */
    @Override
    public Result queryBalanceRecords(Integer current) {
        // 1.当前登录用户（拦截器已保证非空）
        Long userId = UserHolder.getUser().getId();
        // 2.按 create_time 倒序分页查该用户余额流水
        Page<BalanceRecord> page = balanceRecordMapper.selectPage(
                new Page<>(current, SystemConstants.MAX_PAGE_SIZE),
                new QueryWrapper<BalanceRecord>()
                        .eq("user_id", userId)
                        .orderByDesc("create_time"));
        // 3.组装列表项（typeText 文案映射见 BALANCE_TYPE_TEXT）
        List<Map<String, Object>> result = new ArrayList<>(page.getRecords().size());
        for (BalanceRecord record : page.getRecords()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", record.getId());
            item.put("changeAmount", record.getChangeAmount());
            item.put("type", record.getType());
            item.put("typeText", BALANCE_TYPE_TEXT.get(record.getType()));
            item.put("payMethod", record.getPayMethod());
            item.put("description", record.getDescription());
            item.put("createTime", record.getCreateTime());
            result.add(item);
        }
        // 4.返回（total=流水总数，供前端分页）
        return Result.ok(result, page.getTotal());
    }

    /**
     * 【充值功能的实现】模拟充值：微信/支付宝二选一，模拟直接成功（不接真实支付通道）
     * <p>
     * 实现细节（顺序即正确性关键）：
     * - ① 参数校验：amount 非空且 >0 且 ≤5000（元）→ fail"充值金额需大于0且不超过5000元"；
     *   payMethod 仅 wechat/ali 合法 → fail"不支持的支付方式"（模拟充值只支持微信/支付宝）。
     * - ② 当前登录用户：userId 取 UserHolder（登录拦截器保证非空），不信任前端传入。
     * - ③ 模拟直接成功：UPDATE tb_user SET balance=balance+amount WHERE id=我——SQL 原子自增，
     *   避免读-改-写并发覆盖（同一用户并发充值不漏加）；更新行数=0（用户不存在）→ fail"用户不存在"。
     * - ④ 写余额流水：INSERT tb_balance_record(change_amount=+amount, type='recharge',
     *   pay_method=payMethod, description='模拟充值成功')，与③同一 @Transactional 原子提交，
     *   任一失败整体回滚，保证"余额增加"与"流水记录"一致。
     * - ⑤ 返回：ok，data=Map{balance=充值后余额（元）}。
     * @param amount    充值金额（元）
     * @param payMethod 支付方式：wechat=微信 / ali=支付宝
     * @return 充值成功 ok（data=Map{balance}）；参数非法返回对应 fail
     */
    @Override
    @Transactional
    public Result recharge(BigDecimal amount, String payMethod) {
        // ① 参数校验：金额必须 >0 且 ≤5000（元），模拟充值上限防误操作
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0
                || amount.compareTo(new BigDecimal("5000")) > 0) {
            return Result.fail("充值金额需大于0且不超过5000元");
        }
        if (!"wechat".equals(payMethod) && !"ali".equals(payMethod)) {
            return Result.fail("不支持的支付方式");
        }
        // ② 当前登录用户（拦截器已保证非空）
        Long userId = UserHolder.getUser().getId();
        // ③ 模拟直接成功：SQL 原子自增余额（防并发读改写覆盖），未命中说明用户不存在
        boolean updated = update()
                .setSql("balance = balance + " + amount.toPlainString())
                .eq("id", userId)
                .update();
        if (!updated) {
            return Result.fail("用户不存在");
        }
        // ④ 写余额流水（与③同一事务：余额增加与流水记录原子提交）
        BalanceRecord record = new BalanceRecord();
        record.setUserId(userId);
        record.setChangeAmount(amount);
        record.setType("recharge");
        record.setPayMethod(payMethod);
        record.setDescription("模拟充值成功");
        balanceRecordMapper.insert(record);
        // ⑤ 返回充值后余额
        User user = getById(userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("balance", user == null ? null : user.getBalance());
        return Result.ok(data);
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
 *   隐私开关 followVisible/fansVisible/likesVisible/favoritesVisible/commentsVisible/footprintsVisible
 *   仅 0/1、私信模式 dmMode 仅 0/1/2，越界直接 fail 不入库。
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
        if (dto.getLikesVisible() != null && dto.getLikesVisible() != 0 && dto.getLikesVisible() != 1) {
            return Result.fail("隐私设置取值只能为0或1");
        }
        if (dto.getFavoritesVisible() != null && dto.getFavoritesVisible() != 0 && dto.getFavoritesVisible() != 1) {
            return Result.fail("隐私设置取值只能为0或1");
        }
        if (dto.getCommentsVisible() != null && dto.getCommentsVisible() != 0 && dto.getCommentsVisible() != 1) {
            return Result.fail("隐私设置取值只能为0或1");
        }
        if (dto.getFootprintsVisible() != null && dto.getFootprintsVisible() != 0 && dto.getFootprintsVisible() != 1) {
            return Result.fail("隐私设置取值只能为0或1");
        }
        if (dto.getDmMode() != null && dto.getDmMode() != 0 && dto.getDmMode() != 1 && dto.getDmMode() != 2) {
            return Result.fail("私信模式取值只能为0、1或2");
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
            // 【隐私设置的实现】新增四类可见性默认公开（1）+ 私信模式默认开放（0），与建表默认一致
            info.setLikesVisible(1);
            info.setFavoritesVisible(1);
            info.setCommentsVisible(1);
            info.setFootprintsVisible(1);
            info.setDmMode(0);
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
        // 【隐私设置的实现】赞过/收藏/评论/足迹四类可见性 + 私信模式：非空才更新
        if (dto.getLikesVisible() != null) {
            info.setLikesVisible(dto.getLikesVisible());
        }
        if (dto.getFavoritesVisible() != null) {
            info.setFavoritesVisible(dto.getFavoritesVisible());
        }
        if (dto.getCommentsVisible() != null) {
            info.setCommentsVisible(dto.getCommentsVisible());
        }
        if (dto.getFootprintsVisible() != null) {
            info.setFootprintsVisible(dto.getFootprintsVisible());
        }
        if (dto.getDmMode() != null) {
            info.setDmMode(dto.getDmMode());
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

    /**
     * 【隐私设置的实现】校验"我"查看他人隐私数据的可见性（赞过/收藏/评论/足迹四类共用）
     * <p>
     * 实现细节：
     * - 本人始终可见：当前登录用户 id == 目标用户 id 时直接放行（返回 null），隐私开关只影响他人视角。
     * - UserInfo 不存在=默认公开：从未编辑过资料的用户（info==null）或该开关未设置（null）
     *   一律按可见处理，保证老数据/新用户不被误屏蔽。
     * - 判定：flagGetter 取出对应可见性开关（likesVisible/favoritesVisible/commentsVisible/
     *   footprintsVisible），==0（不可见）且非本人 → 返回 fail"对方设置了隐私，该内容不可见"；
     *   其余情况返回 null 放行。
     * - 调用方约定：被查看目标用户需先通过 getById 校验存在（各查询方法已有），
     *   本方法只负责隐私维度，不重复查用户存在性。
     *
     * @param targetUserId 被查看隐私数据的用户id
     * @param flagGetter   取对应可见性开关的函数式引用（如 UserInfo::getLikesVisible）
     * @return null=允许查看；非 null=隐私拦截的失败 Result
     */
    private Result checkPrivacyVisible(Long targetUserId, Function<UserInfo, Integer> flagGetter) {
        // 1.本人始终可见（隐私只影响他人查看）
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser != null && currentUser.getId().equals(targetUserId)) {
            return null;
        }
        // 2.UserInfo 不存在或开关未设置按默认公开处理
        UserInfo info = userInfoService.getById(targetUserId);
        Integer flag = info == null ? null : flagGetter.apply(info);
        if (info == null || flag == null || flag == 1) {
            return null;
        }
        // 3.他人查看且被设置为不可见 → 拦截
        return Result.fail("对方设置了隐私，该内容不可见");
    }

    /**
     * 【主页评论列表的实现】某用户的评论列表（分页，主页"评论" tab）
     * <p>
     * 实现细节：
     * - 归属用户校验：getById(userId) 查不到直接 fail "用户不存在"（他人主页评论 tab 展示前提）。
     * - 分页：commentMapper.selectPage 查 tb_travel_note_comment WHERE user_id=userId，按 create_time
     *   倒序（评论越新越靠前），每页 SystemConstants.MAX_PAGE_SIZE 条；覆盖该用户全部评论
     *   （含一级评论 parentId=0 与回复，主页不做置顶/回复树，统一按时间平铺）。
     * - join 游记：逐条按 note_id 经 noteMapper.selectById 查 tb_travel_note 取标题与 images 首图
     *   （逗号分隔第 1 张，见 firstImage）；游记已被删除时该评论跳过（buildCommentItem 返回 null），
     *   避免主页展示"评论挂在已删游记下"的残缺项。
     * - 返回：data=当前页 List<Map{id, noteId, noteTitle, noteImage, content, createTime}>，
     *   total=该用户评论总数（供前端分页）。
     * - 直接注入 commentMapper/noteMapper 而非评论/游记 Service，避免与 TravelNoteCommentServiceImpl
     *   （其注入 IUserService）形成循环依赖（沿用 queryUserHome 注入 Mapper 的既有约定）。
     * @param userId  被查看评论的用户id
     * @param current 页码，默认第1页
     * @return data=List<Map>，total=评论总数
     */
    @Override
    public Result queryUserComments(Long userId, Integer current) {
        // 1.校验用户存在（他人主页评论 tab 展示前提）
        if (getById(userId) == null) {
            return Result.fail("用户不存在");
        }
        // 1.1.【隐私设置的实现】评论列表可见性校验（他人查看时 comments_visible=0 拦截；本人始终可见）
        Result privacy = checkPrivacyVisible(userId, UserInfo::getCommentsVisible);
        if (privacy != null) {
            return privacy;
        }
        // 2.按 create_time 倒序分页查该用户全部评论（含一级评论与回复）
        Page<TravelNoteComment> page = commentMapper.selectPage(
                new Page<>(current, SystemConstants.MAX_PAGE_SIZE),
                new QueryWrapper<TravelNoteComment>()
                        .eq("user_id", userId)
                        .orderByDesc("create_time"));
        List<TravelNoteComment> records = page.getRecords();
        // 3.逐条 join 游记取标题/首图；游记已删除则跳过该条
        List<Map<String, Object>> result = new ArrayList<>(records.size());
        for (TravelNoteComment record : records) {
            Map<String, Object> item = buildCommentItem(record);
            if (item != null) {
                result.add(item);
            }
        }
        // 4.返回（total=评论总数，供前端分页）
        return Result.ok(result, page.getTotal());
    }

    /**
     * 【主页评论列表的实现】组装单条主页评论列表项（join 游记标题与首图）
     * <p>
     * 实现细节：note_id 经 noteMapper.selectById 查 tb_travel_note——游记不存在（已删除）返回 null，
     * 调用方跳过该条；image 取游记 images 逗号分隔第 1 张（见 firstImage）。
     *
     * @param record 评论记录
     * @return Map{id, noteId, noteTitle, noteImage, content, createTime}；游记已删除返回 null
     */
    private Map<String, Object> buildCommentItem(TravelNoteComment record) {
        // 1.查被评论的游记，已删除则跳过该条
        TravelNote note = noteMapper.selectById(record.getNoteId());
        if (note == null) {
            return null;
        }
        // 2.封装主页评论列表项
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", record.getId());
        item.put("noteId", record.getNoteId());
        item.put("noteTitle", note.getTitle());
        item.put("noteImage", firstImage(note.getImages()));
        item.put("content", record.getContent());
        item.put("createTime", record.getCreateTime());
        return item;
    }

    /**
     * 【赞过列表的实现】某用户赞过的游记列表（分页，主页"赞过" tab 游记子页）
     * <p>
     * 实现细节：
     * - 归属用户校验：getById(userId) 查不到直接 fail "用户不存在"。
     * - 数据来源：赞过记录存于各目标自己的点赞 ZSet——游记 note:liked:{noteId}
     *   （member=userId，score=点赞时间戳）。本接口以"用户"为维度汇总，因此遍历 Redis 全部游记
     *   点赞 key（stringRedisTemplate.keys(NOTE_LIKED_KEY + "*")），逐个用 opsForZSet().score(key, userId)
     *   判定该用户是否赞过（score 非空=赞过，同时拿到点赞时间戳）。
     * - 遍历可行性：demo 规模游记数量有限（百级以内），KEYS 前缀扫描 + 每 key 一次 score 查询开销
     *   可忽略；生产环境数据量大时应建反向索引（如每用户赞过集合）或 DB 表承接，此处按项目规模取舍。
     * - 排序分页：按点赞时间戳（score）倒序（后赞的排前面），收集后手动分页
     *   （每页 SystemConstants.MAX_PAGE_SIZE 条）；total=该用户赞过游记总数（已剔除游记被删除的项，
     *   保证 total 与 data 一致）。
     * - 逐条查游记（noteMapper.selectById），游记已删除跳过；每条返回 Map{id, title, image}。
     * @param userId  被查看赞过列表的用户id
     * @param current 页码，默认第1页
     * @return data=List<Map{id, title, image}>，total=赞过游记总数
     */
    @Override
    public Result queryLikedNotes(Long userId, Integer current) {
        // 1.校验用户存在
        if (getById(userId) == null) {
            return Result.fail("用户不存在");
        }
        // 1.1.【隐私设置的实现】赞过列表可见性校验（他人查看时 likes_visible=0 拦截；本人始终可见）
        Result privacy = checkPrivacyVisible(userId, UserInfo::getLikesVisible);
        if (privacy != null) {
            return privacy;
        }
        // 2.遍历 note:liked:* 收集该用户赞过的游记（id + 点赞时间戳，按时间倒序）
        List<Map<String, Object>> liked = collectLiked(NOTE_LIKED_KEY, userId);
        // 3.分页 + join 游记标题/首图
        return buildLikedPage(liked, current, false);
    }

    /**
     * 【赞过列表的实现】某用户赞过的景点列表（分页，主页"赞过" tab 景点子页）
     * <p>
     * 实现细节：与 queryLikedNotes 完全同构——遍历 Redis 全部景点点赞 key
     * （stringRedisTemplate.keys(ATTRACTION_LIKED_KEY + "*")，即 attraction:liked:*），
     * 用 score(key, userId) 判赞过并取点赞时间戳，按时间倒序手动分页，逐条查 tb_attraction
     * 返回 Map{id, title, image}（景点已删除跳过）；赞过集合由 likeAttraction 维护（无 DB 计数）。
     * @param userId  被查看赞过列表的用户id
     * @param current 页码，默认第1页
     * @return data=List<Map{id, title, image}>，total=赞过景点总数
     */
    @Override
    public Result queryLikedAttractions(Long userId, Integer current) {
        // 1.校验用户存在
        if (getById(userId) == null) {
            return Result.fail("用户不存在");
        }
        // 1.1.【隐私设置的实现】赞过列表可见性校验（他人查看时 likes_visible=0 拦截；本人始终可见）
        Result privacy = checkPrivacyVisible(userId, UserInfo::getLikesVisible);
        if (privacy != null) {
            return privacy;
        }
        // 2.遍历 attraction:liked:* 收集该用户赞过的景点（id + 点赞时间戳，按时间倒序）
        List<Map<String, Object>> liked = collectLiked(ATTRACTION_LIKED_KEY, userId);
        // 3.分页 + join 景点标题/首图
        return buildLikedPage(liked, current, true);
    }

    /**
     * 【赞过列表的实现】遍历某类点赞 ZSet 前缀，收集指定用户赞过的目标（id + 点赞时间戳，按时间倒序）
     * <p>
     * 实现细节：
     * - key 前缀扫描：stringRedisTemplate.keys(keyPrefix + "*") 一次取全量赞过集合 key
     *   （keyPrefix=note:liked: 或 attraction:liked:）；demo 规模目标数量有限，KEYS 阻塞开销可忽略
     *   （生产应换 SCAN 或反向索引，见 queryLikedNotes 注释）。
     * - 判定与取时：每 key 用 opsForZSet().score(key, userId.toString())——score 非空即该用户赞过，
     *   score 值=点赞时间戳（毫秒），天然携带排序依据；member 与 likeTravelNote/likeAttraction
     *   ZADD 写入的 userId.toString() 严格一致。
     * - 脏 key 兜底：key 后缀无法解析为 Long（非本业务前缀命中的脏数据）直接跳过。
     * - 排序：按点赞时间戳倒序（后赞的排前面），供 buildLikedPage 手动分页。
     * @param keyPrefix 赞过集合 key 前缀（NOTE_LIKED_KEY / ATTRACTION_LIKED_KEY）
     * @param userId    目标用户id
     * @return List<Map{id, time}> 按 time 倒序；无赞过记录返回空列表
     */
    private List<Map<String, Object>> collectLiked(String keyPrefix, Long userId) {
        List<Map<String, Object>> liked = new ArrayList<>();
        Set<String> keys = stringRedisTemplate.keys(keyPrefix + "*");
        if (keys == null || keys.isEmpty()) {
            return liked;
        }
        for (String key : keys) {
            // 1.提取 key 后缀的目标 id（note:liked:101 → 101）
            String idStr = key.substring(keyPrefix.length());
            Long targetId;
            try {
                targetId = Long.valueOf(idStr);
            } catch (NumberFormatException e) {
                // 脏 key（前缀命中但后缀非数字）跳过
                continue;
            }
            // 2.score 非空=该用户赞过，score 即点赞时间戳（毫秒）
            Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
            if (score != null) {
                Map<String, Object> item = new HashMap<>(2);
                item.put("id", targetId);
                item.put("time", score.longValue());
                liked.add(item);
            }
        }
        // 3.按点赞时间戳倒序（后赞的排前面）
        liked.sort((a, b) -> Long.compare((Long) b.get("time"), (Long) a.get("time")));
        return liked;
    }

    /**
     * 【赞过列表的实现】把已按点赞时间倒序的赞过列表手动分页，并 join 目标标题/首图
     * <p>
     * 实现细节：
     * - 手动分页：赞过数据在 Redis 内存中（无 DB 分页可走），直接按 from=(current-1)*页大小 截取；
     *   from 越界（current 超总页数）返回空列表，total 恒为该用户赞过总数（与 data 一致）。
     * - join 目标：note 经 noteMapper.selectById 取 title/images 首图；attraction 经
     *   attractionMapper.selectById 取 name（景点无 title 字段，统一以 name 作为 title 输出）与 images 首图；
     *   目标已删除（selectById 返回 null）跳过该条。
     * @param liked      已按 time 倒序的赞过列表（collectLiked 产出）
     * @param current    页码（从1开始）
     * @param attraction true=景点（查 tb_attraction），false=游记（查 tb_travel_note）
     * @return Result.data=List<Map{id, title, image}>，total=赞过总数
     */
    private Result buildLikedPage(List<Map<String, Object>> liked, Integer current, boolean attraction) {
        // 1.手动分页：按点赞时间倒序的整体列表内存截取（目标数量有限）
        int from = (current - 1) * SystemConstants.MAX_PAGE_SIZE;
        int to = Math.min(from + SystemConstants.MAX_PAGE_SIZE, liked.size());
        if (from >= liked.size()) {
            return Result.ok(Collections.emptyList(), (long) liked.size());
        }
        // 2.逐条查目标取标题与首图；目标已删除跳过
        List<Map<String, Object>> result = new ArrayList<>(to - from);
        for (int i = from; i < to; i++) {
            Long targetId = (Long) liked.get(i).get("id");
            Map<String, Object> item = attraction ? buildAttractionLikedItem(targetId) : buildNoteLikedItem(targetId);
            if (item != null) {
                result.add(item);
            }
        }
        // 3.返回（total=该用户赞过总数，前端据此分页）
        return Result.ok(result, (long) liked.size());
    }

    /**
     * 【赞过列表的实现】组装单条赞过游记列表项（join 标题与首图）
     * <p>
     * 实现细节：noteMapper.selectById 查 tb_travel_note——游记不存在（已删除）返回 null，
     * 调用方跳过该条；image 取游记 images 逗号分隔第 1 张（见 firstImage）。
     *
     * @param noteId 游记id
     * @return Map{id, title, image}；游记已删除返回 null
     */
    private Map<String, Object> buildNoteLikedItem(Long noteId) {
        TravelNote note = noteMapper.selectById(noteId);
        if (note == null) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", note.getId());
        item.put("title", note.getTitle());
        item.put("image", firstImage(note.getImages()));
        return item;
    }

    /**
     * 【赞过列表的实现】组装单条赞过景点列表项（join 名称与首图）
     * <p>
     * 实现细节：attractionMapper.selectById 查 tb_attraction——景点不存在（已删除）返回 null，
     * 调用方跳过该条；景点无 title 字段，以 name 作为 title 输出（与收藏列表 buildFavoriteItem 同约定）；
     * image 取景点 images 逗号分隔第 1 张（见 firstImage）。
     *
     * @param attractionId 景点id
     * @return Map{id, title, image}；景点已删除返回 null
     */
    private Map<String, Object> buildAttractionLikedItem(Long attractionId) {
        Attraction attraction = attractionMapper.selectById(attractionId);
        if (attraction == null) {
            return null;
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", attraction.getId());
        item.put("title", attraction.getName());
        item.put("image", firstImage(attraction.getImages()));
        return item;
    }

    /**
     * 【打卡与足迹的实现】某用户的打卡景点列表（分页，足迹展示——**足迹公开**，他人主页也可查看）
     * <p>
     * 实现细节：
     * - 归属用户校验：getById(userId) 查不到直接 fail "用户不存在"（他人主页足迹展示前提）。
     * - 分页：checkinMapper.selectPage 查 tb_attraction_checkin WHERE user_id=userId，按 create_time
     *   倒序（最新打卡在前），每页 SystemConstants.MAX_PAGE_SIZE 条。
     * - join 景点：逐条按 attraction_id 经 attractionMapper.selectById 查 tb_attraction 取名称/首图/坐标
     *   （name/images 首图/x/y/destination）；景点已被删除时该条跳过（buildCheckinItem 返回 null），
     *   避免足迹展示"打卡挂在已删景点下"的残缺项。
     * - 返回：data=当前页 List<Map{id, name, image, x, y, destination, checkinTime}>（id=景点id，
     *   destination=景点所属目的地/城市，checkinTime=打卡时间），total=该用户打卡总数（供前端分页）。
     * - 【打卡与足迹的实现】destination 字段：前端据此按"去过城市"去重统计（如足迹里西湖/灵隐均为杭州），
     *   缺该字段会导致前端城市统计全部命中默认值而失准，故足迹列表项必须携带。
     * - 直接注入 checkinMapper/attractionMapper 而非 Service，避免循环依赖（沿用 queryUserHome 注入 Mapper 约定）。
     * @param userId  被查看足迹的用户id（他人可看：足迹公开）
     * @param current 页码，默认第1页
     * @return data=List<Map{id, name, image, x, y, checkinTime}>，total=打卡总数
     */
    @Override
    public Result queryUserCheckins(Long userId, Integer current) {
        // 1.校验用户存在（他人主页足迹展示前提）
        if (getById(userId) == null) {
            return Result.fail("用户不存在");
        }
        // 1.1.【隐私设置的实现】足迹列表可见性校验（他人查看时 footprints_visible=0 拦截；本人始终可见）
        Result privacy = checkPrivacyVisible(userId, UserInfo::getFootprintsVisible);
        if (privacy != null) {
            return privacy;
        }
        // 2.按 create_time 倒序分页查该用户全部打卡记录（=足迹）
        Page<AttractionCheckin> page = checkinMapper.selectPage(
                new Page<>(current, SystemConstants.MAX_PAGE_SIZE),
                new QueryWrapper<AttractionCheckin>()
                        .eq("user_id", userId)
                        .orderByDesc("create_time"));
        List<AttractionCheckin> records = page.getRecords();
        // 3.逐条 join 景点取名称/首图/坐标；景点已删除则跳过该条
        List<Map<String, Object>> result = new ArrayList<>(records.size());
        for (AttractionCheckin record : records) {
            Map<String, Object> item = buildCheckinItem(record);
            if (item != null) {
                result.add(item);
            }
        }
        // 4.返回（total=打卡总数，供前端分页）
        return Result.ok(result, page.getTotal());
    }

    /**
     * 【打卡与足迹的实现】组装单条足迹列表项（join 景点名称/首图/坐标/目的地）
     * <p>
     * 实现细节：attraction_id 经 attractionMapper.selectById 查 tb_attraction——景点不存在（已删除）
     * 返回 null，调用方跳过该条；image 取景点 images 逗号分隔第 1 张（见 firstImage）；x/y 为景点经纬度
     * （足迹地图点位用），destination=景点所属目的地/城市（tb_attraction.destination，前端"去过城市"
     * 去重统计依赖），checkinTime=打卡记录 create_time。
     *
     * @param record 打卡记录
     * @return Map{id, name, image, x, y, destination, checkinTime}；景点已删除返回 null
     */
    private Map<String, Object> buildCheckinItem(AttractionCheckin record) {
        // 1.查被打卡的景点，已删除则跳过该条
        Attraction attraction = attractionMapper.selectById(record.getAttractionId());
        if (attraction == null) {
            return null;
        }
        // 2.封装足迹列表项
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", attraction.getId());
        item.put("name", attraction.getName());
        item.put("image", firstImage(attraction.getImages()));
        item.put("x", attraction.getX());
        item.put("y", attraction.getY());
        // 【打卡与足迹的实现】目的地/城市字段：前端据此统计"去过哪些城市"（对 destination 去重），缺失会导致城市统计失准
        item.put("destination", attraction.getDestination());
        item.put("checkinTime", record.getCreateTime());
        return item;
    }

    /**
     * 【图片取首图的实现】取 images 逗号分隔的第 1 张图片路径
     * <p>
     * 实现细节：images 为逗号分隔路径（如 /imgs/blogs/4/0.jpg,/imgs/blogs/4/1.jpg），
     * split(",") 取第 1 个；为空返回 null（前端判空兜底）。与收藏列表 firstImage 同约定。
     *
     * @param images 逗号分隔的图片路径
     * @return 第 1 张图片路径；为空返回 null
     */
    private String firstImage(String images) {
        if (StrUtil.isBlank(images)) {
            return null;
        }
        String[] parts = images.split(",");
        return parts.length > 0 ? parts[0] : null;
    }
}
