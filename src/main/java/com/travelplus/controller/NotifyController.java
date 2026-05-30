package com.travelplus.controller;


import com.travelplus.dto.Result;
import com.travelplus.service.INotifyService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * 【通知系统的实现】通知模块接口入口：消息列表、未读数、已读操作（小红书式：赞/关注/评论/回复/@）
 * <p>
 * 实现细节：
 * - 路由域：@RequestMapping("/notify")，挂 /notify/** 前缀；不在 MvcConfig exclude 放行列表，
 *   LoginInterceptor 拦截，未登录访问返回 401（通知是登录用户的私有数据）。
 * - 消息列表 GET /notify/list 按 create_time 倒序分页（每页 SystemConstants.MAX_PAGE_SIZE 条），
 *   支持可选 category 参数按消息分类过滤（likes=赞和收藏 / follows=新增关注 / comments=评论和@，
 *   不传查全部，兼容旧调用），每条含 fromUser{id,nickName,icon}、type、targetId、content、isRead、createTime，
 *   前端据此渲染"谁 赞了/收藏/关注/评论/回复/@ 我"。
 * - 未读数 GET /notify/unread-count 返回本人未读通知数；已读 PUT /notify/read 支持
 *   全部已读（无 body）或单条已读（body {id}），已读语义限定本人消息。
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@RestController
@RequestMapping("/notify")
public class NotifyController {

    @Resource
    private INotifyService notifyService;

    /**
     * 【消息分类的实现】分页查询当前登录用户的通知列表（创建时间倒序，附触发者信息，可按分类过滤）
     * <p>
     * 实现细节：
     * - GET /notify/list?current=页码&category=分类：current 默认第 1 页，每页 SystemConstants.MAX_PAGE_SIZE 条。
     * - category 可选，不传查询全部（与原接口语义完全兼容）；三分类映射（小红书式消息页 Tab）：
     *   likes    → type IN ('like','favorite')  赞和收藏（收藏通知 type=favorite 归入此类）
     *   follows  → type IN ('follow')           新增关注
     *   comments → type IN ('comment','reply','mention')  评论和@（一级评论/回复/@ 合并）
     *   非法 category 由 service 返回 fail"category 参数非法"。
     * - 需登录：/notify/** 被 LoginInterceptor 拦截，未登录返回 401。
     * - data=当前页通知列表（含 fromUser{id,nickName,icon}），total=总条数，供前端分页与"消息页"渲染。
     * @param current  页码，默认第1页
     * @param category 分类（可选）：likes=赞和收藏 / follows=新增关注 / comments=评论和@；空=全部
     */
    @GetMapping("/list")
    public Result queryNotifyList(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "category", required = false) String category) {
        return notifyService.queryNotifyList(current, category);
    }

    /**
     * 【通知系统的实现】查询当前登录用户的未读通知数
     * <p>
     * 实现细节：
     * - GET /notify/unread-count：需登录；data=未读通知数（无未读为 0），
     *   前端 Tab 徽标/角标展示"小红点/数字"。
     */
    @GetMapping("/unread-count")
    public Result unreadCount() {
        return notifyService.unreadCount();
    }

    /**
     * 【分类未读的实现】查询当前登录用户三分类（likes/follows/comments）各自的未读通知数
     * <p>
     * 实现细节：
     * - GET /notify/category-unread-count：需登录。
     * - data=Map{likes: n, follows: n, comments: n}，分类映射与 GET /notify/list?category= 严格一致
     *   （likes=赞和收藏、follows=新增关注、comments=评论和@），消息页三分类圈角标使用。
     */
    @GetMapping("/category-unread-count")
    public Result categoryUnreadCount() {
        return notifyService.categoryUnreadCount();
    }

    /**
     * 【通知系统的实现】标记通知已读：全部已读或单条已读
     * <p>
     * 实现细节：
     * - PUT /notify/read：需登录。
     * - 全部已读：无请求体（body 为空）；单条已读：body {id: 通知id}。
     * - 已读语义限定本人消息（service 内按 user_id=当前用户 过滤），越权操作他人通知不生效。
     * @param body 可选请求体 {id: Long}，空=全部已读
     */
    @PutMapping("/read")
    public Result read(@RequestBody(required = false) Map<String, Long> body) {
        Long id = body == null ? null : body.get("id");
        return notifyService.read(id);
    }

}
