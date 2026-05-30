package com.travelplus.controller;


import com.travelplus.dto.Result;
import com.travelplus.entity.AttractionComment;
import com.travelplus.service.IAttractionCommentService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * 【景点评论的实现】景点评论接口统一入口：发表评论、分页查询评论列表
 * <p>
 * 实现细节：
 * - 路由域：@RequestMapping("/attraction-comment")，挂 /attraction-comment/** 前缀。
 * - 鉴权：本控制器全部接口**需登录**——/attraction-comment/** 不在 MvcConfig LoginInterceptor
 *   exclude 列表（与 /note-comments 同策略），未登录返回 401；评论者 userId 由服务端从 UserHolder 写入，
 *   不信任前端传入，防伪造他人评论。
 * - 结构简化：仿 TravelNoteComment 但**无回复/置顶**——只有发表 + 分页列表两个接口，
 *   评论为一级平铺结构（无 parentId/answerId/is_top），前端按时间倒序渲染。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/attraction-comment")
public class AttractionCommentController {

    @Resource
    private IAttractionCommentService commentService;

    /**
     * 【景点评论的实现】发表景点评论
     * <p>
     * 实现细节：
     * - 请求体：{attractionId, content}，发表成功返回 ok。
     * - 需登录：/attraction-comment 不在拦截器 exclude 列表，未登录返回 401；评论者 userId 由服务端从
     *   UserHolder 取，不信任前端传入；服务端校验景点存在与 content 非空（见 service 注释）。
     * - 评论数由评论表 COUNT 实时统计，不维护 tb_attraction.comments 计数（简化设计，见 service 注释）。
     */
    @PostMapping
    public Result saveComment(@RequestBody AttractionComment comment) {
        return commentService.saveComment(comment);
    }

    /**
     * 【景点评论的实现】分页查询某景点的评论列表（按创建时间倒序）
     * <p>
     * 实现细节：
     * - GET /attraction-comment/of/{attractionId}?current=页码：默认第 1 页，每页 MAX_PAGE_SIZE 条。
     * - 倒序返回（时间越新越靠前），配合前端滚动分页加载；每条评论附带作者昵称/头像（服务端补充）。
     * @param attractionId 景点id
     * @param current      页码，默认第1页，每页 MAX_PAGE_SIZE 条
     */
    @GetMapping("/of/{attractionId}")
    public Result queryComments(@PathVariable("attractionId") Long attractionId,
                                @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return commentService.queryComments(attractionId, current);
    }
}
