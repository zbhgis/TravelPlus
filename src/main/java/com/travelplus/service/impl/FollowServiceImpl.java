package com.travelplus.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.entity.Follow;
import com.travelplus.entity.TravelNote;
import com.travelplus.entity.UserInfo;
import com.travelplus.mapper.FollowMapper;
import com.travelplus.mapper.TravelNoteMapper;
import com.travelplus.service.IFollowService;
import com.travelplus.service.IUserInfoService;
import com.travelplus.service.INotifyService;
import com.travelplus.service.IUserService;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.travelplus.utils.RedisConstants.FEED_KEY;

/**
 * 【关注功能的实现】关注服务实现：关注/取关、是否关注、共同关注、关注/粉丝列表（含隐私校验）
 * <p>
 * 实现细节：
 * - DB 与 Redis 同步：关注关系落 tb_follow（唯一约束 uk_user_follow），Redis Set follows:{userId}
 *   保存关注用户id集合，共同关注（followCommons）直接做集合交集，故两处必须同步维护。
 * - 幂等：关注前先查重（避免重复 insert 触发唯一键冲突），Redis SADD 幂等；取关条件删除天然幂等。
 * - 隐私校验：queryFollowList 查被查看用户 tb_user_info 的 follow_visible/fans_visible，
 *   隐藏且访问者非本人时 fail；主页计数不受隐私影响（列表级隐私）。
 * - 列表查询用"分页查关注关系 → IN 查用户 + ORDER BY FIELD"方式，一次 Set 查询附加 isFollowed 防 N+1。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Slf4j
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private INotifyService notifyService;
    @Resource
    private IUserService userService;
    @Resource
    private IUserInfoService userInfoService;
    @Resource
    private TravelNoteMapper travelNoteMapper;

    /**
     * 【关注功能的实现】关注/取关用户，DB 与 Redis 关注集合同步维护，幂等
     * <p>
     * 实现细节：
     * - 幂等防重：tb_follow 已加唯一约束 uk_user_follow(user_id, follow_user_id)，
     *   且前端乐观更新可能对同一操作重放请求——关注分支 save 前先查该关系是否已存在，
     *   已存在则跳过 insert（避免重复 insert 触发唯一键冲突抛异常），
     *   仅保证 Redis 关注集合成员存在（SADD 天然幂等）后返回 ok。
     * - 取关分支 remove 按 (user_id, follow_user_id) 条件删除，天然幂等（删 0 行同样成功），逻辑不变。
     * - Redis 集合 key 固定为 follows:{当前用户id}，成员为关注用户的 id 字符串；
     *   followCommons（共同关注）依赖该集合做交集，故关注/取关必须与 DB 同步更新，保持两处一致。
     * - 需登录：/follow/** 被 LoginInterceptor 拦截，UserHolder 非空。
     * @param followUserId 被关注/取关的用户id
     * @param isFollow     true=关注，false=取关
     */
    @Override
    public Result follow(Long followUserId, Boolean isFollow) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 2.判断到底是关注还是取关
        if (isFollow) {
            // 3.关注：先查该关系是否已存在（幂等，避免重复 insert 触发唯一约束 uk_user_follow）
            Long existCount = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
            if (existCount == null || existCount == 0) {
                // 3.1.不存在才新增 DB 记录
                Follow follow = new Follow();
                follow.setUserId(userId);
                follow.setFollowUserId(followUserId);
                save(follow);
            }
            // 3.2.无论新增还是已存在，都保证 Redis 关注集合成员存在（SADD 幂等）
            stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            // 3.3.【关注Feed同步的实现】关注成功后把被关注者历史游记补齐到当前用户 Feed 收件箱
            //     （根因：Feed 只在"发布游记时"推送给当时粉丝，关注前已发布的历史游记不会补推，
            //      导致关注后"关注频道"看不到 TA 的历史帖子；见 backfillFollowFeed 注释）
            backfillFollowFeed(followUserId, userId);
            // 3.4.【通知系统的实现】关注动作旁路通知被关注者（仅关注发，取关不发；失败静默）
            notifyFollow(followUserId, userId);
        } else {
            // 4.取关，删除 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            if (isSuccess) {
                // 把关注用户的id从Redis集合中移除（SREM 幂等）
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 【关注Feed同步的实现】关注成功时补齐被关注者历史游记到当前用户 Feed 收件箱
     * <p>
     * 实现细节：
     * - 为什么要补：Feed 推送机制只覆盖"发布游记的那一刻"——saveTravelNote 仅把 noteId 推给
     *   当时的粉丝（feed:{粉丝id}）。若用户在 TA 发布之后才关注，则关注前的历史游记永远不会
     *   出现在自己的 Feed 收件箱里，导致"关注频道"看不到 TA 的帖子。因此关注动作必须主动
     *   把被关注者的全部历史游记一次性补进当前用户的 Feed。
     * - score 语义：ZADD 的 score 用游记 createTime 的 epoch 毫秒（非当前时间戳），保证补入的
     *   历史帖子仍按"真实发布时间"落入 Feed 时间轴——旧帖排后面、新帖排前面，与滚动分页
     *   （ZREVRANGEBYSCORE 时间倒序）语义一致；若用关注时刻作 score，会把历史帖顶到时间轴最前，
     *   排序失真。
     * - 幂等：ZADD 对已存在的 member（noteId）直接更新 score 且不产生重复成员，重复关注/重复调用
     *   不会把帖子复制成多条；Feed 滚动分页按 member=noteId 去重读取，天然安全。
     * - 性能：仅 select id, create_time 两列（不拉全文），一次性 ZADD 全部历史帖子；
     *   demo 规模单用户游记数有限，此处不做分批。
     * - 一致性：DB（tb_follow 落库）+ Redis Set（follows:）与 Feed 补齐放在同一关注分支内，
     *   任一失败仅 log.warn，不影响关注结果（关注主流程已成功，Feed 补齐是增强行为）。
     *
     * @param followUserId 被关注者用户id（其历史游记要补进当前用户 Feed）
     * @param followerId   当前登录用户id（Feed 收件箱归属者）
     */
    private void backfillFollowFeed(Long followUserId, Long followerId) {
        try {
            // 1.查被关注者全部游记，只取 id + create_time（score 排序依据），避免整行全量拉取
            List<TravelNote> notes = travelNoteMapper.selectList(
                    new QueryWrapper<TravelNote>()
                            .select("id", "create_time")
                            .eq("user_id", followUserId));
            if (notes == null || notes.isEmpty()) {
                // 被关注者还没发过游记，无需补齐
                return;
            }
            // 2.逐个把 noteId 写入当前用户 Feed（ZADD 幂等：已存在的 member 只更新 score 不重复）
            String feedKey = FEED_KEY + followerId;
            for (TravelNote note : notes) {
                // createTime 是连接 UTC 会话下读取的 LocalDateTime，按 UTC 转 epoch 毫秒作 score
                long score = note.getCreateTime().atZone(ZoneOffset.UTC).toInstant().toEpochMilli();
                stringRedisTemplate.opsForZSet().add(feedKey, note.getId().toString(), score);
            }
            log.debug("【关注Feed同步的实现】关注用户 {} 时，向 {} 的 Feed 补齐 {} 条历史游记",
                    followUserId, followerId, notes.size());
        } catch (Exception e) {
            // Feed 补齐失败不影响关注结果（主流程已成功），仅记录日志便于排查
            log.warn("【关注Feed同步的实现】关注补齐历史游记失败，不影响关注结果：{}", e.getMessage());
        }
    }

    /**
     * 【通知系统的实现】关注动作旁路通知被关注者（type=follow）
     * <p>
     * 实现细节：
     * - 触发时机：仅关注动作（isFollow=true）调用，取关不发通知。
     * - 文案：纯动作"关注了你"（【通知实时昵称的实现】content 不含昵称前缀，小红书方案）——
     *   昵称由展示层 queryNotifyList 用实时 fromUser.nickName 拼装 displayText，改昵称自动生效。
     * - targetId：被关注者用户id（前端点击消息可跳转到对方主页）。
     * - 静默策略：整体 try-catch，失败仅 log.warn 不影响关注结果；自己关注自己由
     *   NotifyServiceImpl.add 内部自触发过滤兜底。
     *
     * @param followUserId 被关注者用户id（通知接收者）
     * @param followerId   关注动作触发者用户id
     */
    private void notifyFollow(Long followUserId, Long followerId) {
        try {
            // 【通知实时昵称的实现】content 只存纯动作文案"关注了你"，不含昵称前缀：
            // 昵称不落库，由展示层（queryNotifyList）用实时 fromUser.nickName 拼装 displayText，
            // 用户改昵称后历史通知自动显示新昵称（小红书方案：content 纯动作 + 展示实时拼装）。
            notifyService.add(followUserId, followerId, "follow", followUserId, "关注了你");
        } catch (Exception e) {
            log.warn("【通知系统的实现】关注通知失败，不影响关注结果：{}", e.getMessage());
        }
    }

    /**
     * 【关注功能的实现】判断当前登录用户是否已关注指定用户
     * <p>
     * 实现细节：COUNT(tb_follow WHERE user_id=当前登录用户 AND follow_user_id=目标)，
     * count>0 即已关注；直接读 DB 保证与关注/取关落库结果实时一致。
     * @param followUserId 被查询用户id
     * @return Result.data=boolean 是否已关注
     */
    @Override
    public Result isFollow(Long followUserId) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询是否关注 select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Long count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        // 3.判断
        return Result.ok(count > 0);
    }

    /**
     * 【关注功能的实现】查询当前用户与指定用户的共同关注
     * <p>
     * 实现细节：Redis Set follows:{当前用户} ∩ follows:{目标} 一次 SINTER 求交集（O(S1+S2)），
     * 依赖 follow() 关注/取关时对集合的同步维护（否则交集失真）；交集为空返回空列表；
     * 非空则解析 userId，listByIds 批量查用户转 UserDTO 返回。
     * @param id 目标用户id
     * @return 共同关注用户列表（无则空列表）
     */
    @Override
    public Result followCommons(Long id) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 2.求交集
        String key2 = "follows:" + id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key, key2);
        if (intersect == null || intersect.isEmpty()) {
            // 无交集
            return Result.ok(Collections.emptyList());
        }
        // 3.解析id集合
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 4.查询用户
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    /**
     * 【关注粉丝列表的实现】分页查询某用户的关注列表（following）或粉丝列表（fans）
     * <p>
     * 实现细节：
     * - type 二选一，决定查询方向：following=我关注了谁（查 tb_follow.user_id=id 的 follow_user_id 集合）；
     *   fans=谁关注了我（查 tb_follow.follow_user_id=id 的 user_id 集合），非法 type 直接 fail。
     * - 隐私校验（【隐私设置的实现】）：先查被查看用户 id 的 tb_user_info，following 校验 follow_visible、
     *   fans 校验 fans_visible；user_info 不存在 = 未设置隐私 = 默认公开（1）。仅当对应开关为 0（隐藏）
     *   且访问者非本人时才 fail"对方设置了隐私，关注/粉丝列表不可见"——
     *   本人查看自己的列表不受限，未登录（currentUser==null）访问他人隐藏列表同样拒绝。
     *   - 隐私只影响【列表】展示：主页 /user/home/{id} 的 fans/followee【计数】仍实时统计展示
     *     （小红书风格：数量可见、列表按隐私控制）。
     * - 分页策略：先对 tb_follow 用 MyBatis-Plus Page 分页（每页 SystemConstants.MAX_PAGE_SIZE 条，
     *   按 id 倒序保证翻页稳定），拿到当前页的关联用户 id 集合后一次 IN 查 tb_user，
     *   避免一次性查出全部关注关系再做内存分页/全表扫描。
     * - 列表顺序：IN 查询用 ORDER BY FIELD(id,...) 按关联 id 原顺序返回，与关注列表的先后一致。
     * - isFollowed 附加：一次查出当前登录用户关注的全部用户 id 放入 Set，再逐条 O(1) 判断，
     *   避免对每条记录各发一次 count 查询（N+1 问题）。
     * - 需登录：/follow/** 被 LoginInterceptor 拦截，UserHolder 通常非空；仍做防御性判空，
     *   为空时跳过 isFollowed 附加（此时列表正常返回，仅关注态为 null）。
     * @param id      被查看的用户id
     * @param type    列表类型：following=关注列表，fans=粉丝列表（必填）
     * @param current 页码，默认第1页，每页 SystemConstants.MAX_PAGE_SIZE 条
     */
    @Override
    public Result queryFollowList(Long id, String type, Integer current) {
        // 1.校验 type 参数
        if (StrUtil.isBlank(type) || (!"following".equals(type) && !"fans".equals(type))) {
            return Result.fail("type 参数必须为 following 或 fans");
        }
        // 2.隐私校验：查看他人设为隐藏的关注/粉丝列表时拒绝（列表级隐私，不影响主页计数）
        UserDTO currentUser = UserHolder.getUser();
        // 2.1.查被查看用户（id）的隐私设置；user_info 不存在 = 未设置隐私 = 默认公开
        UserInfo targetInfo = userInfoService.getById(id);
        boolean visible = true;
        if (targetInfo != null) {
            if ("following".equals(type)) {
                // 关注列表受 follow_visible 控制（null 视为默认公开 1）
                visible = targetInfo.getFollowVisible() == null || targetInfo.getFollowVisible() == 1;
            } else {
                // 粉丝列表受 fans_visible 控制（null 视为默认公开 1）
                visible = targetInfo.getFansVisible() == null || targetInfo.getFansVisible() == 1;
            }
        }
        // 2.2.不可见且访问者非本人 → 拒绝；未登录（currentUser==null）访问他人隐藏列表同样拒绝；
        //     本人查看自己的列表不受隐私限制；user_info 不存在（默认公开）正常放行
        if (!visible && (currentUser == null || !currentUser.getId().equals(id))) {
            return Result.fail("对方设置了隐私，关注/粉丝列表不可见");
        }
        // 3.按方向分页查询关注关系（id 倒序保证分页稳定）
        Page<Follow> followPage = new Page<>(current, SystemConstants.MAX_PAGE_SIZE);
        QueryWrapper<Follow> wrapper = new QueryWrapper<>();
        if ("following".equals(type)) {
            // 我关注了谁
            wrapper.eq("user_id", id);
        } else {
            // 谁关注了我
            wrapper.eq("follow_user_id", id);
        }
        wrapper.orderByDesc("id");
        this.page(followPage, wrapper);
        List<Follow> records = followPage.getRecords();
        if (records.isEmpty()) {
            return Result.ok(Collections.emptyList(), followPage.getTotal());
        }
        // 4.提取当前页关联的用户id集合
        List<Long> ids = "following".equals(type)
                ? records.stream().map(Follow::getFollowUserId).collect(Collectors.toList())
                : records.stream().map(Follow::getUserId).collect(Collectors.toList());
        // 5.一次 IN 查询用户并保持列表顺序（ORDER BY FIELD）
        String idStr = StrUtil.join(",", ids);
        List<UserDTO> users = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 6.附加"当前登录用户是否已关注"（一次查出关注集合，逐条 O(1) 判断；currentUser 已在隐私校验处取得）
        if (currentUser != null) {
            Set<Long> followingIds = query().eq("user_id", currentUser.getId()).list().stream()
                    .map(Follow::getFollowUserId)
                    .collect(Collectors.toSet());
            users.forEach(u -> u.setIsFollowed(followingIds.contains(u.getId())));
        }
        // 7.返回列表与总数
        return Result.ok(users, followPage.getTotal());
    }
}
