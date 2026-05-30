package com.travelplus.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.entity.ChatHidden;
import com.travelplus.entity.ChatMessage;
import com.travelplus.entity.ChatPin;
import com.travelplus.entity.Follow;
import com.travelplus.entity.User;
import com.travelplus.entity.UserInfo;
import com.travelplus.mapper.ChatHiddenMapper;
import com.travelplus.mapper.ChatMessageMapper;
import com.travelplus.mapper.ChatPinMapper;
import com.travelplus.mapper.FollowMapper;
import com.travelplus.service.IChatService;
import com.travelplus.service.IUserInfoService;
import com.travelplus.service.IUserService;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.travelplus.utils.RedisConstants.CHAT_STRANGER_KEY;

/**
 * 【私信功能的实现】私信服务实现：发送（互关限制）、会话历史、会话列表、未读数、互关状态
 * <p>
 * 实现细节：
 * - send：参数与接收用户校验 → 按接收方 dm_mode 三档分流（关闭/仅互关/开放）→ 开放且非互关
 *   走"对方回复前每日 1 条"Redis 计数（回复过即解锁）→ 插入消息返回 id。
 * - list：双向条件 (from=me AND to=peer) OR (from=peer AND to=me) 按 create_time 倒序分页，
 *   返回前把"对方发给我"的未读批量置读（翻看即已读，见 list 方法注释）；
 *   返回全部历史消息（不再按 is_deleted_by_me 过滤——会话隐藏方案消息永不删）。
 * - sessions：一条 SQL 取全部与我相关的消息（create_time 倒序），内存按对方 id 聚合出
 *   最后消息与未读数，一次 listByIds 批量补用户信息（避免 N+1）；
 *   聚合前自动解除已恢复的隐藏会话（对方隐藏后新发消息 → 删除隐藏记录）。
 * - deleteSession（【私信会话隐藏的实现】）：删除会话=隐藏视图——只 INSERT tb_chat_hidden，
 *   不再删消息、不再标记 is_deleted_by_me；隐藏恢复两条路径：对方新消息自动解除（sessions
 *   聚合）或我主动打开聊天页/发消息（list/send 主动解除），会话连同完整历史恢复。
 * - pin/unpin（【会话置顶的实现】）：会话置顶/取消置顶——只写 tb_chat_pin 置顶记录，幂等
 *   （uk_pin 冲突即忽略）；sessions 置顶会话排最前（先置顶组后普通组，组内各自 lastTime 倒序）。
 * - mutualStatus：tb_follow 双向 count==2 判定互关，前端据此展示限制提示。
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@Slf4j
@Service
public class ChatServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements IChatService {

    @Resource
    private IUserService userService;

    /** 【私信限制的实现】读取接收方私信模式（tb_user_info.dm_mode）用，UserInfo 不存在时按默认开放处理 */
    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 直接注入 FollowMapper 而非 IFollowService：互关判定只需一条 COUNT 查询，
     * 且 IFollowService 实现类注入了 IUserService/INotifyService，直接注入 Mapper 无循环依赖风险。
     */
    @Resource
    private FollowMapper followMapper;

    /** 【私信会话隐藏的实现】会话隐藏表 Mapper：删除会话=INSERT 隐藏记录；sessions 排除 + 自动解除基于本表 */
    @Resource
    private ChatHiddenMapper chatHiddenMapper;

    /** 【会话置顶的实现】会话置顶表 Mapper：置顶=INSERT 置顶记录；sessions 排序 + 取消置顶基于本表 */
    @Resource
    private ChatPinMapper chatPinMapper;

    /** 私信陌生人计数 key 的日期段格式：chat:stranger:{from}:{to}:{yyyyMMdd} */
    private static final DateTimeFormatter LIMIT_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * 【私信功能的实现】发送一条私信（含私信模式三档限制 + 陌生人回复前每日单条）
     * <p>
     * 实现细节：
     * - 校验顺序：toUserId 非空且不能是自己 → content 非空且 ≤500 → 接收用户存在（getById 判空）。
     * - 私信限制（【私信限制的实现】）分三层，按接收方 dm_mode 分流：
     *   1. dm_mode=2（关闭）：无论是否互关一律 fail"对方已关闭私信功能"。
     *   2. dm_mode=1（仅互关）：非互关 fail"对方仅允许互关好友私信"，互关放行。
     *   3. dm_mode=0（开放，默认，UserInfo 不存在同此）：互关→不限；非互关→走 checkStrangerLimit
     *      ——对方回复过我（from=对方 AND to=我 存在记录）则不限，未回复则按 Redis
     *      chat:stranger:{from}:{to}:{yyyyMMdd} 计数，当日首次=1 放行、>1 fail
     *      "对方尚未回复你，今天最多发送一条消息"（对方回复后当天即可继续对话）。
     * - 插入：fromUserId=当前用户、toUserId=对方、content、isRead=0（createTime 走 DB 默认值），
     *   save 后主键回填。
     * - 【私信会话隐藏的实现】主动恢复：插入消息成功后 DELETE tb_chat_hidden WHERE user_id=fromUserId
     *   AND peer_id=toUserId——我主动给对方发消息 = 主动恢复会话（删除会话只是从我的列表移除，主动
     *   再发消息即恢复），保证发送成功后会话在列表中正常显示；幂等——无隐藏记录时删除 0 行无副作用。
     * - 返回 data=消息 id（供前端本地回显）。
     */
    @Override
    public Result send(Long toUserId, String content) {
        // 1.获取当前登录用户（/chat/** 已由 LoginInterceptor 拦截，防御性判空）
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        Long fromUserId = currentUser.getId();
        // 2.参数校验：接收者非空、不能给自己发私信（避免自说自话污染会话列表）、内容非空 ≤500
        if (toUserId == null) {
            return Result.fail("接收用户不能为空");
        }
        if (fromUserId.equals(toUserId)) {
            return Result.fail("不能给自己发私信");
        }
        if (StrUtil.isBlank(content)) {
            return Result.fail("消息内容不能为空");
        }
        if (content.length() > 500) {
            return Result.fail("消息内容不能超过500字");
        }
        // 3.校验接收用户存在（getById 判空，避免向已删除/不存在的用户发信）
        if (userService.getById(toUserId) == null) {
            return Result.fail("接收用户不存在");
        }
        // 4.私信限制（【私信限制的实现】）：按接收方 dm_mode 三档分流；dm_mode=0 开放且非互关时
        //   校验"对方回复前每日最多 1 条"（回复过即解锁，当天可继续对话）
        boolean mutual = isMutual(fromUserId, toUserId);
        Result dmCheck = checkDmAllowed(toUserId, mutual);
        if (dmCheck != null) {
            return dmCheck;
        }
        if (!mutual) {
            Result strangerCheck = checkStrangerLimit(fromUserId, toUserId);
            if (strangerCheck != null) {
                return strangerCheck;
            }
        }
        // 5.插入消息（is_read 默认 0 未读，createTime 由 DB DEFAULT CURRENT_TIMESTAMP 生成）
        ChatMessage message = new ChatMessage();
        message.setFromUserId(fromUserId);
        message.setToUserId(toUserId);
        message.setContent(content);
        message.setIsRead(0);
        save(message);
        // 6.【私信会话隐藏的实现】我主动给对方发消息 = 主动恢复会话：删除可能存在的隐藏记录
        //   （DELETE tb_chat_hidden WHERE user_id=fromUserId AND peer_id=toUserId），保证发送成功后
        //   会话在列表中正常显示；幂等——无隐藏记录时删除 0 行，无任何副作用。
        chatHiddenMapper.delete(new QueryWrapper<ChatHidden>()
                .eq("user_id", fromUserId).eq("peer_id", toUserId));
        // 7.返回 ok（含消息 id，供前端本地回显）
        return Result.ok(message.getId());
    }

    /**
     * 【私信功能的实现】分页查询"我"与 peer 的历史私信（创建时间倒序，返回时把对方发来的未读置读）
     * <p>
     * 实现细节：
     * - 【私信会话隐藏的实现】前置处理（主动恢复）：我打开该会话聊天页 = 主动恢复会话——若 tb_chat_hidden
     *   仍存在 user_id=我 AND peer_id=peer 记录，先 DELETE 该隐藏记录（我主动再次进入聊天即恢复，微信语义），
     *   再继续正常查询。隐藏恢复共两条路径：① 对方在隐藏时间后给我发新消息（sessions 聚合自动恢复）；
     *   ② 我主动打开聊天页（list 自动解除）或主动发消息（send 自动解除）。
     * - 查询：(from_user_id=我 AND to_user_id=peer) OR (from_user_id=peer AND to_user_id=我)，
     *   按 create_time 倒序 + id 倒序分页（每页 SystemConstants.MAX_PAGE_SIZE 条）——倒序=最新在前，
     *   前端拿到后倒过来即可正序展示；同一秒多条时 id 倒序保证翻页稳定不重复。
     * - 历史完整（【私信会话隐藏的实现】）：不再按 is_deleted_by_me 过滤——会话隐藏方案消息永不删除，
     *   一旦会话恢复（对方新消息触发或我主动进入/发消息），这里返回从第一条到最新的全部历史，不丢任何一条。
     * - 已读标记时机（【私信限制的实现】）：打开/翻看会话即视为已读——取页数据后立即执行
     *   UPDATE tb_chat_message SET is_read=1 WHERE to_user_id=我 AND from_user_id=peer AND is_read=0，
     *   一次 UPDATE 覆盖全部未读（含不在本页的更早消息），保证会话列表未读数随之归零；
     *   同时把当前页中"对方发给我"的记录 isRead 置 1，让返回数据与库状态一致。
     * - 返回：data=当前页消息列表（ChatMessage{id,fromUserId,toUserId,content,isRead,createTime}），
     *   total=总条数（供前端"加载更多"分页）。
     */
    @Override
    public Result list(Long peerId, Integer current) {
        // 1.获取当前登录用户
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        Long me = currentUser.getId();
        if (peerId == null) {
            return Result.fail("参数 peerId 不能为空");
        }
        // 2.【私信会话隐藏的实现】前置处理：我主动打开该会话聊天页 = 主动恢复会话——若 tb_chat_hidden
        //   仍存在 user_id=我 AND peer_id=peer 的隐藏记录，直接 DELETE 该记录（微信语义：删除会话只是
        //   从我的列表移除，我主动再次进入聊天即主动恢复，历史完整、正常聊天）；幂等——无记录时删除 0 行
        //   无副作用。隐藏恢复共两条路径：① 对方在隐藏时间后给我发新消息（sessions 聚合自动恢复）；
        //   ② 我主动打开聊天页（此处自动解除）或主动发消息（send 自动解除，见 send 方法）。
        chatHiddenMapper.delete(new QueryWrapper<ChatHidden>()
                .eq("user_id", me).eq("peer_id", peerId));
        // 3.双向条件分页查询全部历史（我→peer 或 peer→我，覆盖完整会话；无 is_deleted_by_me 过滤——
        //   会话隐藏方案消息永不删除，恢复后历史完整返回）
        QueryWrapper<ChatMessage> wrapper = new QueryWrapper<>();
        wrapper.and(w -> w.eq("from_user_id", me).eq("to_user_id", peerId))
                .or(w -> w.eq("from_user_id", peerId).eq("to_user_id", me));
        wrapper.orderByDesc("create_time").orderByDesc("id");
        Page<ChatMessage> page = page(new Page<>(current == null ? 1 : current, SystemConstants.MAX_PAGE_SIZE), wrapper);
        List<ChatMessage> records = page.getRecords();
        // 4.已读标记时机（【私信限制的实现】）：翻看即已读——批量把 peer 发给我的未读置 1
        update().set("is_read", 1).eq("to_user_id", me).eq("from_user_id", peerId).eq("is_read", 0).update();
        // 5.同步返回数据：当前页中"对方发给我"的消息 isRead 置 1（与库中状态一致，前端免重算）
        records.forEach(msg -> {
            if (msg.getToUserId() != null && msg.getToUserId().equals(me)) {
                msg.setIsRead(1);
            }
        });
        // 6.返回（倒序=最新在前，前端倒过来展示）
        return Result.ok(records, page.getTotal());
    }

    /**
     * 【私信功能的实现】会话列表：与我聊过天的用户聚合，按最后消息时间倒序
     * <p>
     * 实现细节：
     * - 聚合方式（避免 N+1）：一条 SQL 查出全部 (from_user_id=我 OR to_user_id=我) 的消息，
     *   按 create_time 倒序；内存用 LinkedHashMap 按"对方 id"分组——插入序即消息倒序，
     *   每个会话第一次出现的那条就是最后一条消息（lastContent/lastTime）。
     * - 【私信会话隐藏的实现】隐藏会话排除：先查我的全部隐藏记录（tb_chat_hidden WHERE user_id=我），
     *   聚合循环中跳过被隐藏的 peer（我的视角该会话不显示）。
     * - 【私信会话隐藏的实现】自动解除（会话恢复）：对每条隐藏记录，若对方（peer_id）在隐藏时间之后
     *   给我发过新消息（存在 from_user_id=peer_id AND to_user_id=我 AND create_time &gt; 隐藏时间 的消息），
     *   说明对方有新消息来找我 → 立即删除该隐藏记录（DELETE tb_chat_hidden），该会话正常显示，
     *   且历史完整（隐藏期间消息从未删除，list 可返回全部）。
     * - 未读数：遍历时累计"对方发给我的未读"（to_user_id=我 AND is_read=0）。
     * - 用户信息：聚合完成后一次 userService.listByIds(对方id集) 批量查询昵称/头像，
     *   不再对每个会话单独查用户（无 N+1）。
     * - 置顶（【会话置顶的实现】）：聚合后查 tb_chat_pin WHERE user_id=我 得已置顶 peer 集合，
     *   每个会话项加 isPinned 标志；排序=先置顶组后普通组，组内各自按 lastTime 倒序（置顶前排）。
     * - 返回：data=List&lt;Map&gt;：{peer:{id,nickName,icon}, lastContent, lastTime, unread, isPinned}，
     *   LinkedHashMap 保序=置顶在前、普通在后，组内均按 lastTime 倒序。
     */
    @Override
    public Result sessions() {
        // 1.获取当前登录用户
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        Long me = currentUser.getId();
        // 2.【私信会话隐藏的实现】查我的隐藏记录 + 自动解除已恢复的会话（对方隐藏后新发消息）：
        //   先从 tb_chat_hidden 取全部 user_id=我 的记录，逐条判断"对方是否有隐藏时间之后发给我的消息"，
        //   有则 DELETE 隐藏记录（会话恢复、历史完整保留）；没有则记入 hiddenPeerIds 供聚合跳过。
        List<ChatHidden> hiddenList = chatHiddenMapper.selectList(
                new QueryWrapper<ChatHidden>().eq("user_id", me));
        Set<Long> hiddenPeerIds = new HashSet<>();
        // 全部消息先取出来，用于在内存中判断"隐藏时间之后是否有对方新消息"（一次查询，避免每条隐藏记录再查库）
        QueryWrapper<ChatMessage> allWrapper = new QueryWrapper<>();
        allWrapper.and(w -> w.eq("from_user_id", me).or(x -> x.eq("to_user_id", me)));
        allWrapper.orderByDesc("create_time").orderByDesc("id");
        List<ChatMessage> all = list(allWrapper);
        for (ChatHidden hidden : hiddenList) {
            Long peerId = hidden.getPeerId();
            // 对方在隐藏时间之后给我发过消息 → 有新消息 → 自动解除隐藏（删除记录，会话恢复显示）
            boolean hasNewMessageAfterHide = all.stream().anyMatch(msg ->
                    peerId.equals(msg.getFromUserId()) && me.equals(msg.getToUserId())
                            && msg.getCreateTime() != null && hidden.getCreateTime() != null
                            && msg.getCreateTime().isAfter(hidden.getCreateTime()));
            if (hasNewMessageAfterHide) {
                chatHiddenMapper.deleteById(hidden.getId());
            } else {
                // 仍隐藏：聚合时跳过该 peer
                hiddenPeerIds.add(peerId);
            }
        }
        if (all.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 3.内存聚合：LinkedHashMap 保插入序（=最后消息时间倒序）；先出现者即该会话的最后一条
        Map<Long, Map<String, Object>> sessionMap = new LinkedHashMap<>();
        Set<Long> peerIds = new HashSet<>();
        for (ChatMessage msg : all) {
            // 对方 id：发给我的取 from，我发出去的取 to
            Long peerId = msg.getFromUserId().equals(me) ? msg.getToUserId() : msg.getFromUserId();
            // 【私信会话隐藏的实现】跳过仍隐藏的会话：我的视角不显示该 peer（对方无新消息，隐藏状态持续）
            if (hiddenPeerIds.contains(peerId)) {
                continue;
            }
            Map<String, Object> session = sessionMap.computeIfAbsent(peerId, k -> {
                peerIds.add(k);
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("lastContent", msg.getContent());
                s.put("lastTime", msg.getCreateTime());
                s.put("unread", 0);
                return s;
            });
            // 未读数累计：对方发给我的且未读
            if (msg.getToUserId().equals(me) && msg.getIsRead() != null && msg.getIsRead() == 0) {
                session.put("unread", (Integer) session.get("unread") + 1);
            }
        }
        // 【私信会话隐藏的实现】所有会话均被隐藏（无任何可见 peer）时直接返回空列表：
        //   避免 listByIds(空集合) 生成 "WHERE id IN ( )" 的非法 SQL（MySQL 语法错误）
        if (sessionMap.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 4.批量查询会话对方用户信息（一次 IN 查询，避免 N+1）
        Map<Long, User> userMap = userService.listByIds(peerIds).stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        // 5.【会话置顶的实现】查我的置顶记录：一次查询拿到全部已置顶 peer 的 id 集合
        //   （置顶只影响我的视图排序，不碰消息与隐藏表；无置顶时集合为空，会话按原排序返回）
        Set<Long> pinnedPeerIds = chatPinMapper.selectList(
                new QueryWrapper<ChatPin>().eq("user_id", me)).stream()
                .map(ChatPin::getPeerId)
                .collect(Collectors.toSet());
        // 6.组装返回（插入序=最后消息时间倒序）；同时按"是否置顶"拆两组合并：
        //   置顶组在前、普通组在后，组内各自保持 lastTime 倒序（置顶会话排列表最前）
        List<Map<String, Object>> result = new ArrayList<>();
        List<Map<String, Object>> pinnedResult = new ArrayList<>();
        for (Map.Entry<Long, Map<String, Object>> entry : sessionMap.entrySet()) {
            Map<String, Object> session = entry.getValue();
            // 会话对方的昵称/头像（用户已删除时兜底空串，前端判空展示默认）
            User peer = userMap.get(entry.getKey());
            Map<String, Object> peerVo = new LinkedHashMap<>();
            peerVo.put("id", entry.getKey());
            peerVo.put("nickName", peer == null ? "" : peer.getNickName());
            peerVo.put("icon", peer == null ? "" : peer.getIcon());
            session.put("peer", peerVo);
            // 【会话置顶的实现】加 isPinned 标志（true=已置顶）：置顶的进前置组，其余进普通组
            boolean isPinned = pinnedPeerIds.contains(entry.getKey());
            session.put("isPinned", isPinned);
            (isPinned ? pinnedResult : result).add(session);
        }
        // 合并：先置顶组后普通组（组内已各自保持 lastTime 倒序）
        pinnedResult.addAll(result);
        return Result.ok(pinnedResult);
    }

    /**
     * 【私信功能的实现】统计我收到的未读私信总数（前端角标）
     * <p>
     * 实现细节：SELECT COUNT(*) FROM tb_chat_message WHERE to_user_id=我 AND is_read=0；
     * 【私信会话隐藏的实现】追加排除隐藏会话的对方消息：NOT EXISTS
     * (SELECT 1 FROM tb_chat_hidden h WHERE h.user_id=我 AND h.peer_id=tb_chat_message.from_user_id)
     * ——我隐藏的会话对方来的消息不参与未读角标（与 sessions 隐藏语义一致，我的视角不显示该会话）；
     * 对方新发消息后 sessions 会自动解除隐藏，解除后未读数自然恢复统计；
     * count 恒非空（MySQL COUNT 无匹配返回 0），null 兜底为 0 防 NPE。
     */
    @Override
    public Result unreadCount() {
        // 1.获取当前登录用户
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        Long me = currentUser.getId();
        // 2.统计收到的未读条数（【私信会话隐藏的实现】排除我隐藏会话的对方消息，隐藏=我的视角不显示该会话未读）
        Long count = query().eq("to_user_id", me).eq("is_read", 0)
                .notExists("SELECT 1 FROM tb_chat_hidden h WHERE h.user_id = " + me
                        + " AND h.peer_id = tb_chat_message.from_user_id")
                .count();
        // 3.返回（count==null 兜底 0）
        return Result.ok(count == null ? 0 : count);
    }

    /**
     * 【私信限制的实现】判断"我"与 peer 是否互相关注（小红书式私信豁免判据）
     * <p>
     * 实现细节：一条 COUNT 查询同时校验双向关注——
     * SELECT COUNT(*) FROM tb_follow WHERE (user_id=me AND follow_user_id=peer)
     * OR (user_id=peer AND follow_user_id=me)；tb_follow 有 uk_user_follow(user_id,follow_user_id)
     * 唯一约束，故最多存在两条，count==2 即为互关。用 OR 一条 SQL 而非两次查询，省一次往返。
     */
    private boolean isMutual(Long me, Long peerId) {
        Long count = followMapper.selectCount(new QueryWrapper<Follow>()
                .and(w -> w.eq("user_id", me).eq("follow_user_id", peerId))
                .or(w -> w.eq("user_id", peerId).eq("follow_user_id", me)));
        return count != null && count == 2L;
    }

    /**
     * 【私信限制的实现】校验接收方的私信模式（tb_user_info.dm_mode）三档是否允许本次发送
     * <p>
     * 实现细节：
     * - 数据源：userInfoService.getById(接收方id) 读 tb_user_info.dm_mode；UserInfo 不存在
     *   （从未编辑过资料）按默认 dm_mode=0 开放处理，保证老用户/未建资料用户不受限。
     * - 三档判定：
     *   0=开放：任何人可发，返回 null 放行（非互关的"回复前每日 1 条"在 send 内另由
     *   checkStrangerLimit 校验，不在此处处理）。
     *   1=仅互关：非互关 fail"对方仅允许互关好友私信"；互关放行。
     *   2=关闭：任何情况 fail"对方已关闭私信功能"（含互关，彻底关闭不区分关系）。
     * - dm_mode 非法值（脏数据）兜底按 0 开放处理。
     *
     * @param toUserId 接收方用户id
     * @param mutual   "我"与接收方是否已互相关注（send 内已判定，传入复用免二次查询）
     * @return null=允许继续发送；非 null=携带失败信息的 Result，send 直接返回
     */
    private Result checkDmAllowed(Long toUserId, boolean mutual) {
        UserInfo info = userInfoService.getById(toUserId);
        Integer dmMode = info == null ? null : info.getDmMode();
        if (dmMode != null && dmMode == 2) {
            // 关闭：无论是否互关都不可发
            return Result.fail("对方已关闭私信功能");
        }
        if (dmMode != null && dmMode == 1 && !mutual) {
            // 仅互关：非互关拒发，互关放行
            return Result.fail("对方仅允许互关好友私信");
        }
        return null;
    }

    /**
     * 【私信限制的实现】开放模式下非互关用户的"对方回复前每日最多 1 条"校验（Redis 计数 + TTL 自然日重置）
     * <p>
     * 实现细节：
     * - 为什么回复前限 1 条/天：非互关说明对方未主动建立双向关系，连续单向私信属于潜在骚扰；
     *   限"对方回复前每天 1 条"既保留陌生人首次搭话的通道，又强制等对方回复才能继续——
     *   对方一旦回过（说明愿意对话）当天即可继续，无需再等。
     * - 回复判定：查 tb_chat_message 是否存在 from_user_id=对方 AND to_user_id=我 的记录
     *   （count>0 即回复过我，含历史任意一天）——有则返回 null 直接放行，不占当日计数。
     * - key 设计：chat:stranger:{fromUserId}:{toUserId}:{yyyyMMdd}——以"发送者+接收者+自然日"
     *   为粒度独立计数，A→B 与 B→A 互不影响，换一天自动换 key。
     * - 计数 + TTL：发送前 INCR（Redis 原子自增，并发安全）；返回 1 说明是当日首次，随即
     *   EXPIRE 到当日 24:00 的剩余秒数——TTL 到点 key 自动消失，次日从 1 重新计数，无需定时任务。
     * - 判定：INCR 结果 > 1 即超限，返回 fail"对方尚未回复你，今天最多发送一条消息"；
     *   INCR 返回 null（Redis 异常）时按放行处理，避免 Redis 故障阻断正常私信。
     *
     * @param fromUserId 发送方（当前登录用户）
     * @param toUserId   接收方
     * @return null=允许发送；非 null=超限失败 Result
     */
    private Result checkStrangerLimit(Long fromUserId, Long toUserId) {
        // 1.回复解锁：对方回复过我（from=对方 AND to=我）则不限，当天可继续对话
        Long replyCount = query().eq("from_user_id", toUserId).eq("to_user_id", fromUserId).count();
        if (replyCount != null && replyCount > 0) {
            return null;
        }
        // 2.组装当日计数 key：chat:stranger:{from}:{to}:{yyyyMMdd}
        String key = CHAT_STRANGER_KEY + fromUserId + ":" + toUserId + ":" + LIMIT_DATE_FORMAT.format(LocalDate.now());
        // 3.原子自增（Redis INCR，同一 key 并发安全）
        Long count = stringRedisTemplate.opsForValue().increment(key);
        // 4.首次（返回值==1）设置 TTL 为当日剩余秒数：自然日到期 key 自动删除，次日重新计数
        if (count != null && count == 1L) {
            long secondsToMidnight = Duration.between(LocalDateTime.now(),
                    LocalDate.now().plusDays(1).atStartOfDay()).getSeconds();
            stringRedisTemplate.expire(key, secondsToMidnight, TimeUnit.SECONDS);
        }
        // 5.超限判定（count==null 视为 Redis 异常，放行避免阻断正常私信；>1 即今日已发过 1 条）
        if (count != null && count > 1) {
            return Result.fail("对方尚未回复你，今天最多发送一条消息");
        }
        return null;
    }

    /**
     * 【私信限制的实现】"我"与 peer 是否互相关注（供前端展示限制提示）
     * <p>
     * 实现细节：复用 isMutual 判定，data=Map{mutual: boolean}；
     * 前端在聊天页打开时调用，mutual=false 时提示"互关后可无限私信；对方未回复时每日仅可发 1 条"，
     * 具体拦截规则以 send 的 dm_mode 三档 + 陌生人单条为准。
     */
    @Override
    public Result mutualStatus(Long peerId) {
        // 1.获取当前登录用户
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        Long me = currentUser.getId();
        if (peerId == null) {
            return Result.fail("参数 peerId 不能为空");
        }
        // 2.互关判定
        boolean mutual = isMutual(me, peerId);
        // 3.返回
        Map<String, Object> data = new HashMap<>();
        data.put("mutual", mutual);
        return Result.ok(data);
    }

    /**
     * 【私信会话隐藏的实现】删除"我"与 peer 的会话（微信/小红书式会话隐藏：只影响我的视图，消息永不删除）
     * <p>
     * 实现细节（会话隐藏语义）：
     * - 删除=隐藏：仅 INSERT tb_chat_hidden(user_id=我, peer_id=peerId) 一条隐藏记录，不再删任何消息、
     *   不再标记 is_deleted_by_me——消息永久保留在库中，我的 list/sessions 只是不显示该会话；
     *   对方视角完全不受影响，对方会话与完整历史原样保留。
     * - 幂等：tb_chat_hidden 有唯一约束 uk_hide(user_id, peer_id)，重复删除（重复隐藏）时 INSERT
     *   命中唯一键抛 DuplicateKeyException，捕获后按"已隐藏"忽略，结果仍是 ok，不产生脏数据。
     * - 自动恢复：对方在隐藏时间之后给我发新消息 → sessions 聚合时检测到新消息自动删除本隐藏记录
     *   （见 sessions 方法），会话连同完整历史重新显示——删除会话只隐藏，不造成任何历史丢失。
     * - 删除不涉及 Redis（私信未入 Redis，无缓存需清理）。
     *
     * @param peerId 会话对象用户id
     * @return 删除成功返回 ok
     */
    @Override
    public Result deleteSession(Long peerId) {
        // 1.获取当前登录用户
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        Long me = currentUser.getId();
        if (peerId == null) {
            return Result.fail("参数 peerId 不能为空");
        }
        // 2.【私信会话隐藏的实现】插入隐藏记录（不删消息、不标记 is_deleted_by_me）；
        //   幂等靠唯一约束 uk_hide(user_id, peer_id)：重复隐藏 INSERT 冲突 → 捕获 DuplicateKeyException 忽略
        ChatHidden hidden = new ChatHidden();
        hidden.setUserId(me);
        hidden.setPeerId(peerId);
        try {
            chatHiddenMapper.insert(hidden);
        } catch (DuplicateKeyException e) {
            // 已隐藏过：幂等忽略，会话仍处于隐藏状态
            log.debug("会话已隐藏，重复删除幂等忽略：user={}, peer={}", me, peerId);
        }
        // 3.返回 ok（会话删除=隐藏成功，前端刷新会话列表即可）
        return Result.ok();
    }

    /**
     * 【会话置顶的实现】置顶"我"与 peer 的会话（会话列表前排展示，只影响我的视图）
     * <p>
     * 实现细节（会话置顶语义）：
     * - 置顶=固定前排：仅 INSERT tb_chat_pin(user_id=我, peer_id=peerId) 一条置顶记录——不碰消息、
     *   不碰 tb_chat_hidden（隐藏与置顶相互独立），对方视角完全不受影响；sessions 聚合后查本表
     *   把置顶会话排到最前（先置顶组后普通组，组内各自 lastTime 倒序）。
     * - 幂等：tb_chat_pin 有唯一约束 uk_pin(user_id, peer_id)，重复置顶时 INSERT 命中唯一键抛
     *   DuplicateKeyException，捕获后按"已置顶"忽略，结果仍是 ok，不产生脏数据。
     * - 置顶不涉及 Redis（私信未入 Redis，无缓存需清理）。
     *
     * @param peerId 会话对象用户id
     * @return 置顶成功返回 ok
     */
    @Override
    public Result pin(Long peerId) {
        // 1.获取当前登录用户
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        Long me = currentUser.getId();
        if (peerId == null) {
            return Result.fail("参数 peerId 不能为空");
        }
        // 2.【会话置顶的实现】插入置顶记录（不碰消息、不碰隐藏表）；
        //   幂等靠唯一约束 uk_pin(user_id, peer_id)：重复置顶 INSERT 冲突 → 捕获 DuplicateKeyException 忽略
        ChatPin pin = new ChatPin();
        pin.setUserId(me);
        pin.setPeerId(peerId);
        try {
            chatPinMapper.insert(pin);
        } catch (DuplicateKeyException e) {
            // 已置顶过：幂等忽略，会话仍处于置顶状态
            log.debug("会话已置顶，重复置顶幂等忽略：user={}, peer={}", me, peerId);
        }
        // 3.返回 ok（置顶成功，前端刷新会话列表即可）
        return Result.ok();
    }

    /**
     * 【会话置顶的实现】取消置顶"我"与 peer 的会话（回到按 lastTime 排序的普通位置）
     * <p>
     * 实现细节（会话置顶语义）：
     * - 取消置顶=删除置顶记录：DELETE FROM tb_chat_pin WHERE user_id=我 AND peer_id=peerId；
     *   未置顶时删除 0 行也按 ok 返回（幂等、无副作用）。删除后 sessions 中该会话 isPinned=false
     *   回到按 lastTime 倒序的普通位置。
     * - 不碰消息、不碰隐藏表，只影响我的视图排序；不涉及 Redis，无缓存需清理。
     *
     * @param peerId 会话对象用户id
     * @return 取消置顶成功返回 ok
     */
    @Override
    public Result unpin(Long peerId) {
        // 1.获取当前登录用户
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        Long me = currentUser.getId();
        if (peerId == null) {
            return Result.fail("参数 peerId 不能为空");
        }
        // 2.【会话置顶的实现】删除置顶记录（WHERE user_id=我 AND peer_id=peerId）；
        //   未置顶时 delete 返回 0，同样按取消成功处理（幂等）
        chatPinMapper.delete(new QueryWrapper<ChatPin>().eq("user_id", me).eq("peer_id", peerId));
        // 3.返回 ok（取消置顶成功，前端刷新会话列表即可）
        return Result.ok();
    }

}
