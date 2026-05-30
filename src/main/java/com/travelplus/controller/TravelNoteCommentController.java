package com.travelplus.controller;


import com.travelplus.dto.Result;
import com.travelplus.entity.TravelNoteComment;
import com.travelplus.service.ITravelNoteCommentService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * 【评论模块接口的实现】游记评论统一入口：发表评论、分页查询评论列表
 * <p>
 * 实现细节：
 * - 路由域：@RequestMapping("/note-comments")，挂 /note-comments/** 前缀。
 * - 鉴权：发表评论（POST）需登录；查询评论列表匿名可访问（无 token 时 RefreshTokenInterceptor
 *   放行且 UserHolder 为空，服务端不依赖登录态）。
 * - 评论者身份由服务端从 UserHolder 写入，不信任前端传入，防伪造他人评论。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/note-comments")
public class TravelNoteCommentController {

    @Resource
    private ITravelNoteCommentService commentService;

    /**
     * 【评论功能的实现】发表评论
     * <p>
     * 实现细节：
     * - 请求体：{noteId, content, parentId?, answerId?}，发表成功返回 ok。
     * - 需登录：/note-comments 不在拦截器 exclude 列表，未登录返回 401；评论者 userId 由服务端从 UserHolder 取，
     *   不信任前端传入，防止伪造他人评论。
     * - 发表成功后服务端同步把 tb_travel_note.comments 计数 +1，保证游记详情页评论数与列表一致。
     */
    @PostMapping
    public Result saveComment(@RequestBody TravelNoteComment comment) {
        return commentService.saveComment(comment);
    }

    /**
     * 【评论功能的实现】分页查询某游记的评论列表（按创建时间升序）
     * <p>
     * 实现细节：
     * - GET /note-comments/of/{noteId}?current=页码：默认第 1 页，每页 SystemConstants.MAX_PAGE_SIZE 条。
     * - 升序返回（时间越早越靠前），配合前端滚动分页加载；每条评论附带作者昵称/头像（服务端补充）。
     * @param noteId  游记id
     * @param current 页码，默认第1页，每页 SystemConstants.MAX_PAGE_SIZE 条
     */
    @GetMapping("/of/{noteId}")
    public Result queryComments(@PathVariable("noteId") Long noteId,
                                @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return commentService.queryComments(noteId, current);
    }

    /**
     * 【评论删除的实现】删除评论（评论作者或该游记作者可删）
     * <p>
     * 实现细节：
     * - DELETE /note-comments/{id} 会被 LoginInterceptor 拦截，未登录返回 401；
     *   权限校验在 service 内完成（评论作者或游记作者任一即可），无权限 fail"无权删除"。
     * - 删除该评论及其直接回复（parent_id=id），并同步游记 comments 计数 -（1+被删回复数）。
     * @param id 评论id
     */
    @DeleteMapping("/{id}")
    public Result deleteComment(@PathVariable("id") Long id) {
        return commentService.deleteComment(id);
    }

    /**
     * 【评论置顶的实现】置顶/取消置顶一级评论（仅游记作者可操作）
     * <p>
     * 实现细节：
     * - PUT /note-comments/top/{id}，请求体 {top: true|false}（top=true 置顶，false 取消置顶）；
     *   需登录（LoginInterceptor 拦截），权限校验在 service 内完成（仅游记作者），无权限 fail"无权操作"。
     * - 同一游记最多置顶 3 条，超限时先取消最旧的置顶（见 service 实现注释）。
     * @param id   评论id
     * @param body 请求体 {top: Boolean}，top 缺失时 fail"参数不完整"
     */
    @PutMapping("/top/{id}")
    public Result topComment(@PathVariable("id") Long id,
                             @RequestBody(required = false) java.util.Map<String, Boolean> body) {
        Boolean top = body == null ? null : body.get("top");
        if (top == null) {
            return Result.fail("参数不完整");
        }
        return commentService.topComment(id, top);
    }

}
