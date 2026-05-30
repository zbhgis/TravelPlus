package com.travelplus.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelplus.dto.Result;
import com.travelplus.entity.TravelNoteComment;
import com.travelplus.entity.User;
import com.travelplus.mapper.TravelNoteCommentMapper;
import com.travelplus.service.ITravelNoteCommentService;
import com.travelplus.service.ITravelNoteService;
import com.travelplus.service.IUserService;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 【评论功能的实现】游记评论服务实现：发表评论（同步游记评论数）与分页查询评论列表（补用户信息）
 * <p>
 * 实现细节：
 * - saveComment：评论者 userId 取自 UserHolder，校验 noteId/content，补齐 parentId/answerId/liked/status
 *   默认值，落库成功后才同步 tb_travel_note.comments + 1（setSql 自增）。
 * - queryComments：note_id 等值 + create_time 升序分页（每页 MAX_PAGE_SIZE），每条经 IUserService 补
 *   name/icon（实体 @TableField(exist=false) 字段，仅本次响应使用），用户不存在给"匿名"兜底。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Service
public class TravelNoteCommentServiceImpl extends ServiceImpl<TravelNoteCommentMapper, TravelNoteComment> implements ITravelNoteCommentService {

    @Resource
    private IUserService userService;

    @Resource
    private ITravelNoteService travelNoteService;

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
        return Result.ok();
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
        // 1.按创建时间升序分页查询该游记的评论
        Page<TravelNoteComment> page = query()
                .eq("note_id", noteId)
                .orderByAsc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<TravelNoteComment> records = page.getRecords();
        // 2.为每条评论补充用户昵称与头像
        records.forEach(comment -> {
            User user = userService.getById(comment.getUserId());
            if (user == null) {
                comment.setName("匿名");
                return;
            }
            comment.setName(user.getNickName());
            comment.setIcon(user.getIcon());
        });
        // 3.返回
        return Result.ok(records);
    }
}
