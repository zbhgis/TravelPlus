package com.travelplus.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelplus.dto.Result;
import com.travelplus.entity.TravelNote;
import com.travelplus.entity.TravelNoteComment;
import com.travelplus.entity.User;
import com.travelplus.mapper.TravelNoteCommentMapper;
import com.travelplus.service.INotifyService;
import com.travelplus.service.ITravelNoteCommentService;
import com.travelplus.service.ITravelNoteService;
import com.travelplus.service.IUserService;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 【评论功能的实现】游记评论服务实现：发表评论（同步游记评论数+旁路通知）、分页查询评论列表
 * （补用户信息+置顶排序）、删除评论、置顶评论
 * <p>
 * 实现细节：
     * - saveComment：评论者 userId 取自 UserHolder，校验 noteId/content，补齐 parentId/answerId/liked/status
     *   默认值，落库成功后才同步 tb_travel_note.comments + 1（setSql 自增），随后旁路触发通知
     *   （一级评论→游记作者 comment、回复→被回复评论作者 reply、content 内 @昵称→被@用户 mention，
     *   通知失败静默不影响发表结果）。【消息数据补真的实现】自评论/自回复/@自己 同样通知自己
     *   （NotifyServiceImpl.add 不再排除接收者==触发者，用户要求消息页显示自触发通知）。
 * - queryComments：note_id 等值 + is_top desc + create_time 升序分页（每页 MAX_PAGE_SIZE），
 *   内存排序为"一级评论在前（置顶优先）→ 回复跟在所属一级评论之后"的平铺结构，每条经 IUserService
 *   补 name/icon（实体 @TableField(exist=false) 字段，仅本次响应使用），用户不存在给"匿名"兜底。
 * - deleteComment：评论作者或游记作者可删；删除该评论及其直接回复（parent_id=id），同步游记
 *   comments 计数 -（1+被删回复数）。
 * - topComment：仅游记作者可置顶/取消，仅一级评论可置顶；同一游记最多置顶 MAX_TOP_COMMENT(3) 条，
 *   置顶超限时取消最旧置顶。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Slf4j
@Service
public class TravelNoteCommentServiceImpl extends ServiceImpl<TravelNoteCommentMapper, TravelNoteComment> implements ITravelNoteCommentService {

    /** 【评论置顶的实现】同一游记最多置顶的一级评论数 */
    private static final int MAX_TOP_COMMENT = 3;

    /** 【通知系统的实现】@ 昵称解析正则：@ 后跟非空白、非@、非中文句读的连续字符（昵称最多32字符） */
    private static final Pattern MENTION_PATTERN = Pattern.compile("@([^\\s@，。！？]+)");

    @Resource
    private IUserService userService;

    @Resource
    private ITravelNoteService travelNoteService;

    @Resource
    private INotifyService notifyService;

    /**
     * 【评论功能的实现】发表评论
     * <p>
     * 实现细节：
     * - 需登录：评论者 userId 从 UserHolder.getUser().getId() 获取并覆盖实体字段，
     *   前端请求必须带有效 token（/note-comments 不在拦截器 exclude 列表），未登录返回 401。
     * - 参数校验：noteId/content 任一为空 fail "评论参数不完整"，不落库。
     * - 默认字段补齐：parentId/answerId 为空置 0（一级评论、非回复）、liked 为空置 0、
     *   status 强制置 true（=1 正常），保证列表渲染不出现 null。
     * - 落库成功后才同步 tb_travel_note.comments + 1（setSql("comments = comments + 1") 自增，
     *   经 ITravelNoteService.update），评论插入失败则不更新计数，保证评论数与列表数据一致。
     */
    @Override
    public Result saveComment(TravelNoteComment comment) {
        // 1.获取登录用户id
        Long userId = UserHolder.getUser().getId();
        comment.setUserId(userId);
        // 2.校验参数
        if (comment.getNoteId() == null || StrUtil.isBlank(comment.getContent())) {
            return Result.fail("评论参数不完整");
        }
        // 3.补齐默认字段
        if (comment.getParentId() == null) {
            comment.setParentId(0L); // 一级评论，关联的1级评论id为0
        }
        if (comment.getAnswerId() == null) {
            comment.setAnswerId(0L); // 一级评论，回复的评论id为0
        }
        if (comment.getLiked() == null) {
            comment.setLiked(0); // 初始点赞数0
        }
        comment.setStatus(true); // status = 1
        // 4.插入评论
        boolean isSuccess = save(comment);
        if (!isSuccess) {
            return Result.fail("发表评论失败");
        }
        // 5.同步游记评论数 +1
        travelNoteService.update()
                .setSql("comments = comments + 1")
                .eq("id", comment.getNoteId())
                .update();
        // 6.【通知系统的实现】旁路通知：评论/回复/@ 通知，失败静默不影响发表结果
        notifyCommentRelated(comment);
        return Result.ok();
    }

    /**
     * 【通知系统的实现】评论成功后的旁路通知（评论/回复/@）
     * <p>
     * 实现细节：
     * - 一级评论（parentId=0）：通知游记作者，type=comment，targetId=游记id。
     * - 回复（parentId!=0）：通知被回复评论的作者（查 parentId 对应评论的 userId），type=reply。
     * - @ 解析：从 content 用正则 @([^\s@，。！？]+) 提取昵称，命中既有用户（tb_user.nick_name）则
     *   通知该用户，type=mention；【消息数据补真的实现】@ 到自己昵称也通知自己（自@通知，
     *   用户要求）；与评论/回复接收者（receiverId）重复时合并为一条，不重复打扰。
     * - 整个通知块 try-catch 静默：通知是旁路数据，任何失败不影响评论发表结果（与 NotifyServiceImpl.add
     *   的静默策略叠加，双保险）。
     *
     * @param comment 已落库成功的评论
     */
    private void notifyCommentRelated(TravelNoteComment comment) {
        try {
            // 1.评论/回复通知：确定接收者
            Long receiverId = null;
            if (comment.getParentId() == null || comment.getParentId() == 0) {
                // 1.1.一级评论 → 通知游记作者（【消息数据补真的实现】自评论：作者==评论者同样通知自己）
                TravelNote note = travelNoteService.getById(comment.getNoteId());
                if (note != null && note.getUserId() != null) {
                    receiverId = note.getUserId();
                    notifyService.add(receiverId, comment.getUserId(), "comment", comment.getNoteId(),
                            // 【通知实时昵称的实现】content 只存纯动作"评论了你的游记《{标题前20字}》"，
                            // 不含昵称前缀——昵称由展示层 queryNotifyList 用实时 fromUser.nickName
                            // 拼装 displayText（改昵称后历史通知自动显示新昵称，小红书方案）
                            "评论了你的游记《" + noteTitle(note) + "》");
                }
            } else {
                // 1.2.回复 → 通知被回复评论的作者（自回复：被回复作者==回复者同样通知自己）
                TravelNoteComment parent = getById(comment.getParentId());
                if (parent != null && parent.getUserId() != null) {
                    receiverId = parent.getUserId();
                    notifyService.add(receiverId, comment.getUserId(), "reply", comment.getNoteId(),
                            // 【通知实时昵称的实现】content 只存纯动作"回复了你的评论"，
                            // 不含昵称前缀——昵称由展示层实时拼装 displayText（小红书方案）
                            "回复了你的评论");
                }
            }
            // 2.@ 解析通知（自@也通知；与评论/回复接收者重复时合并去重）
            notifyMentionUsers(comment, receiverId);
        } catch (Exception e) {
            log.warn("【通知系统的实现】评论通知失败，不影响评论发表：{}", e.getMessage());
        }
    }

    /**
     * 【通知系统的实现】@ 昵称解析与 mention 通知
     * <p>
     * 实现细节：
     * - 解析规则：正则 @([^\s@，。！？]+) 提取 content 中所有 @后的昵称片段（昵称不含空白/@/中文句读），
     *   用 MENTION_PATTERN 全局匹配。
     * - 命中判断：去重后一次 IN 查 tb_user.nick_name（IN 查询避免逐昵称 N+1），只通知昵称真实存在的用户。
     * - 【消息数据补真的实现】去重合并：@ 到自己昵称同样通知自己（自@通知，用户要求）；
     *   @ 到的用户与评论/回复接收者相同则合并（该用户已收到 comment/reply 通知，避免一条消息发两条）；
     *   同昵称被 @ 多次经 distinct 去重。
     *
     * @param comment    评论
     * @param receiverId 评论/回复通知的接收者id（用于与 mention 合并去重，可为 null）
     */
    private void notifyMentionUsers(TravelNoteComment comment, Long receiverId) {
        // 1.提取 content 中所有 @昵称
        List<String> mentions = new ArrayList<>();
        if (comment.getContent() != null) {
            Matcher matcher = MENTION_PATTERN.matcher(comment.getContent());
            while (matcher.find()) {
                mentions.add(matcher.group(1));
            }
        }
        if (mentions.isEmpty()) {
            return;
        }
        // 2.去重后一次 IN 查询命中既有用户的昵称
        List<String> distinctMentions = mentions.stream().distinct().collect(Collectors.toList());
        List<User> hitUsers = userService.query().in("nick_name", distinctMentions).list();
        for (User hit : hitUsers) {
            // 3.【消息数据补真的实现】@ 到自己昵称同样通知自己（自@通知，不再排除——用户要求）
            //    与评论/回复接收者重复时合并（已收到 comment/reply 通知，不再重复发 mention）
            if (receiverId != null && hit.getId().equals(receiverId)) {
                continue;
            }
            notifyService.add(hit.getId(), comment.getUserId(), "mention", comment.getNoteId(),
                    // 【通知实时昵称的实现】content 只存纯动作"在评论中提到了你"，
                    // 不含昵称前缀——昵称由展示层实时拼装 displayText（小红书方案）
                    "在评论中提到了你");
        }
    }

    /**
     * 【通知实时昵称的实现】当前登录用户昵称的旧辅助方法 currentNickName() 已删除：
     * 通知 content 改为纯动作文案后，昵称不再在调用点拼装，统一由展示层
     * NotifyServiceImpl.queryNotifyList 用实时 fromUser.nickName 拼装 displayText（小红书方案）。
     */

    /**
     * 【通知系统的实现】游记标题展示文案（超 20 字截断加省略号，避免通知文案过长）
     */
    private String noteTitle(TravelNote note) {
        String title = note.getTitle();
        if (title == null) {
            return "";
        }
        return title.length() > 20 ? title.substring(0, 20) + "…" : title;
    }

    /**
     * 【评论功能的实现】分页查询某游记的评论列表
     * <p>
     * 实现细节：
     * - 查询：note_id = noteId 按 create_time 升序分页（时间越早越靠前），
     *   每页 SystemConstants.MAX_PAGE_SIZE 条，页码从 1 开始。
     * - 用户信息补充：每条评论经 IUserService.getById(comment.getUserId()) 取用户，
     *   昵称/头像写入实体上 @TableField(exist=false) 的 name/icon 字段
     *   （复用 TravelNoteServiceImpl 补用户信息的模式，不落库，仅本次响应使用）。
     * - 兜底策略：用户不存在（如账号已删除）时 name 置"匿名"、icon 保持 null，评论列表仍可正常渲染。
     */
    @Override
    public Result queryComments(Long noteId, Integer current) {
        // 1.分页查询该游记的评论（置顶优先 + 创建时间升序，保证置顶评论进入首页）
        Page<TravelNoteComment> page = query()
                .eq("note_id", noteId)
                .orderByDesc("is_top")
                .orderByAsc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<TravelNoteComment> records = page.getRecords();
        // 2.【评论置顶的实现】排序：一级评论在前（置顶优先，其余按创建时间升序），回复跟在所属一级评论之后（平铺返回，前端按 parentId 组装）
        records = sortComments(records);
        // 3.为每条评论补充用户昵称与头像
        records.forEach(comment -> {
            User user = userService.getById(comment.getUserId());
            if (user == null) {
                comment.setName("匿名");
                return;
            }
            comment.setName(user.getNickName());
            comment.setIcon(user.getIcon());
        });
        // 4.返回
        return Result.ok(records);
    }

    /**
     * 【评论置顶的实现】评论平铺排序：置顶优先 + 一级评论在前 + 回复跟随所属一级评论
     * <p>
     * 实现细节：
     * - 排序策略：一级评论（parent_id=0）is_top=1 置顶优先，其余一级评论按创建时间升序；
     *   回复（parent_id!=0）跟在所属一级评论之后（按所属一级评论的排序位置排列，同级回复内按创建时间升序），
     *   不在当前页一级评论下的回复排最后（前端按 parentId 组装，跨页的父子由前端滚动加载补全）。
     * - 返回结构：一级评论 + 各自回复平铺成一个 List（含每条 isTop 字段），不做树形嵌套，
     *   前端用 parentId 字段组装父子关系，与 saveComment 的 parentId/answerId 语义一致。
     *
     * @param comments 分页查出的评论集合
     * @return 排序后的平铺评论列表
     */
    private List<TravelNoteComment> sortComments(List<TravelNoteComment> comments) {
        // 1.拆分为一级评论与回复
        List<TravelNoteComment> level1 = new ArrayList<>();
        List<TravelNoteComment> replies = new ArrayList<>();
        for (TravelNoteComment comment : comments) {
            if (comment.getParentId() != null && comment.getParentId() != 0) {
                replies.add(comment); // parentId!=0 为回复
            } else {
                level1.add(comment); // parentId=0 为一级评论
            }
        }
        // 2.一级评论：置顶(is_top=1)优先，其余按创建时间升序
        level1.sort(Comparator
                .comparingInt((TravelNoteComment c) -> c.getIsTop() != null && c.getIsTop() == 1 ? 0 : 1)
                .thenComparing(Comparator.nullsLast(Comparator.comparing(TravelNoteComment::getCreateTime))));
        // 3.建立"一级评论id -> 排序位置"映射，回复按所属一级评论的位置排列
        Map<Long, Integer> level1Index = new HashMap<>();
        for (int i = 0; i < level1.size(); i++) {
            level1Index.put(level1.get(i).getId(), i);
        }
        // 4.回复：跟随所属一级评论之后（父级不在当前页时排最后），同级内按创建时间升序
        replies.sort(Comparator
                .comparingInt((TravelNoteComment c) -> level1Index.getOrDefault(c.getParentId(), level1.size()))
                .thenComparing(Comparator.nullsLast(Comparator.comparing(TravelNoteComment::getCreateTime))));
        // 5.平铺返回：一级评论在前、各自回复随后
        List<TravelNoteComment> sorted = new ArrayList<>(level1.size() + replies.size());
        sorted.addAll(level1);
        sorted.addAll(replies);
        return sorted;
    }

    /**
     * 【评论删除的实现】删除评论（评论作者或该游记作者可删）
     * <p>
     * 实现细节：
     * - 权限校验：评论作者（comment.userId == 当前登录用户）或该游记作者（note.userId == 当前登录用户）
     *   有权删除，两者任一满足即可；都不满足 fail"无权删除"。
     * - 级联删除：删除该评论本身 + 其直接回复（parent_id = id，评论只有两级结构，回复不嵌套回复）。
     * - 计数同步：游记 comments 同步 -（1 + 被删回复数），用 COALESCE/GREATEST 兜底 null 与负数。
     *
     * @param id 评论id
     * @return 删除成功返回 ok；评论不存在 fail"评论不存在"；无权限 fail"无权删除"
     */
    @Override
    public Result deleteComment(Long id) {
        // 1.查询评论，不存在则幂等失败
        TravelNoteComment comment = getById(id);
        if (comment == null) {
            return Result.fail("评论不存在");
        }
        // 2.权限校验：评论作者 或 该游记作者
        Long currentUserId = UserHolder.getUser().getId();
        TravelNote note = travelNoteService.getById(comment.getNoteId());
        boolean isCommentAuthor = currentUserId.equals(comment.getUserId());
        boolean isNoteAuthor = note != null && note.getUserId() != null && currentUserId.equals(note.getUserId());
        if (!isCommentAuthor && !isNoteAuthor) {
            return Result.fail("无权删除");
        }
        // 3.统计该评论的直接回复数（parent_id = id）
        Long replyCount = query().eq("parent_id", id).count();
        // 4.删除该评论及其直接回复（评论两级结构，删除回复不需要继续向下级联）
        removeById(id);
        remove(new QueryWrapper<TravelNoteComment>().eq("parent_id", id));
        // 5.同步游记评论数 -（1 + 被删回复数），COALESCE 兜底 null、GREATEST 防止负数
        long deletedCount = (replyCount == null ? 0 : replyCount) + 1;
        travelNoteService.update()
                .setSql("comments = GREATEST(COALESCE(comments, 0) - " + deletedCount + ", 0)")
                .eq("id", comment.getNoteId())
                .update();
        return Result.ok();
    }

    /**
     * 【评论置顶的实现】置顶/取消置顶一级评论（仅游记作者可操作，同一游记最多置顶 3 条）
     * <p>
     * 实现细节：
     * - 权限校验：仅该游记作者（note.userId == 当前登录用户）可置顶/取消，否则 fail"无权操作"；
     *   仅一级评论（parent_id=0）可置顶，回复不可置顶。
     * - 置顶上限：同一游记已置顶数达到 MAX_TOP_COMMENT(3) 时，先取消"最旧的置顶"（id 最小=最早置顶）
     *   再置顶当前评论，保证最多同时置顶 3 条。
     * - 取消置顶：top=false 直接把该评论 is_top 置 0。
     *
     * @param id  评论id
     * @param top true=置顶，false=取消置顶
     * @return 操作成功返回 ok；评论不存在 fail"评论不存在"；无权限/回复 fail"无权操作"
     */
    @Override
    public Result topComment(Long id, Boolean top) {
        // 1.查询评论，不存在则失败
        TravelNoteComment comment = getById(id);
        if (comment == null) {
            return Result.fail("评论不存在");
        }
        // 2.仅一级评论（parent_id=0）可置顶
        if (comment.getParentId() != null && comment.getParentId() != 0) {
            return Result.fail("无权操作");
        }
        // 3.权限校验：仅游记作者可置顶/取消
        Long currentUserId = UserHolder.getUser().getId();
        TravelNote note = travelNoteService.getById(comment.getNoteId());
        if (note == null || note.getUserId() == null || !currentUserId.equals(note.getUserId())) {
            return Result.fail("无权操作");
        }
        if (top != null && top) {
            // 4.置顶：同一游记最多置顶 MAX_TOP_COMMENT 条，超限先取消最旧的置顶
            Long topCount = query().eq("note_id", comment.getNoteId()).eq("is_top", 1).count();
            if (topCount != null && topCount >= MAX_TOP_COMMENT) {
                TravelNoteComment oldestTop = query()
                        .eq("note_id", comment.getNoteId())
                        .eq("is_top", 1)
                        .orderByAsc("id") // id 最小=最早置顶
                        .last("limit 1")
                        .one();
                if (oldestTop != null) {
                    update().set("is_top", 0).eq("id", oldestTop.getId()).update();
                }
            }
            // 4.1.置顶当前评论
            update().set("is_top", 1).eq("id", id).update();
        } else {
            // 5.取消置顶
            update().set("is_top", 0).eq("id", id).update();
        }
        return Result.ok();
    }
}
