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

}
