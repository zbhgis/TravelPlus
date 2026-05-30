package com.travelplus.controller;


import com.travelplus.dto.Result;
import com.travelplus.service.IChatService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * 【私信功能的实现】私信模块接口入口：发送私信、会话历史、会话列表、未读数、互关状态（小红书式互关限制）
 * <p>
 * 实现细节：
 * - 路由域：@RequestMapping("/chat")，挂 /chat/** 前缀；不在 MvcConfig exclude 放行列表，
 *   LoginInterceptor 拦截，未登录访问返回 401（私信是登录用户的私有数据）。
 * - 发送 POST /chat/send：body {toUserId, content}，服务端校验接收用户存在、内容 ≤500，
 *   非互关用户每日限 3 条（Redis 计数 + 自然日 TTL），超限 fail"对方未关注你，今日私信次数已用完"；
 *   成功后 data=消息 id。
 * - 历史 GET /chat/list?peerId=&current=：与 peer 的私信按 create_time 倒序分页（每页
 *   SystemConstants.MAX_PAGE_SIZE 条），返回全部历史消息（【私信会话隐藏的实现】消息永不删除），
 *   返回时把对方发来的未读批量置读（翻看即已读），data=List{ChatMessage}，total=总条数；
 *   若我仍隐藏该会话（tb_chat_hidden 有记录）则自动解除隐藏（我主动打开聊天页 = 主动恢复会话，
 *   微信语义），继续正常返回完整历史。
 * - 会话列表 GET /chat/sessions：与我聊过天的用户聚合（内存聚合避免 N+1），
 *   每条含 peer{id,nickName,icon}、lastContent、lastTime、unread，按 lastTime 倒序；
 *   【私信会话隐藏的实现】聚合排除我隐藏的 peer（tb_chat_hidden），对方在隐藏时间之后发来
 *   新消息 → 自动解除隐藏并正常显示（会话恢复、历史完整）。
 * - 未读数 GET /chat/unread-count：返回 data=我收到的未读私信总数（前端角标）；
 *   【私信会话隐藏的实现】排除我隐藏会话的对方消息（NOT EXISTS tb_chat_hidden）。
 * - 互关状态 GET /chat/mutual-status?peerId=：返回 data=Map{mutual: boolean}，
 *   前端展示"互关后可无限私信"限制提示。
 * - 会话置顶（【会话置顶的实现】）：PUT /chat/pin/{peerId} 置顶、DELETE /chat/pin/{peerId} 取消置顶，
 *   只写 tb_chat_pin（幂等），sessions 中置顶会话排最前（先置顶组后普通组，组内各自 lastTime 倒序）。
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Resource
    private IChatService chatService;

    /**
     * 【私信功能的实现】发送一条私信（含互关限制）
     * <p>
     * 实现细节：
     * - POST /chat/send：需登录；body {toUserId: 接收者id, content: 消息内容}。
     * - 服务端校验：接收用户存在、content 非空且 ≤500、不能给自己发私信。
     * - 互关限制（【私信限制的实现】）：互相关注豁免；非互关时每日对同一接收者最多 3 条，
     *   超出 fail"对方未关注你，今日私信次数已用完"（Redis 计数 key=chat:limit:{from}:{to}:{yyyyMMdd}，
     *   TTL=当日剩余秒数，自然日自动重置）。
     * - 成功返回 data=消息 id。
     * @param body 请求体 {toUserId: Long, content: String}
     */
    @PostMapping("/send")
    public Result send(@RequestBody Map<String, Object> body) {
        // 从 body 解析参数（风格同 /notify/read 的 Map 接收，字段缺失时按 null 交给 service 校验）
        Long toUserId = null;
        String content = null;
        if (body != null) {
            Object to = body.get("toUserId");
            toUserId = to == null ? null : Long.valueOf(String.valueOf(to));
            Object c = body.get("content");
            content = c == null ? null : String.valueOf(c);
        }
        return chatService.send(toUserId, content);
    }

    /**
     * 【私信功能的实现】分页查询"我"与 peer 的历史私信（创建时间倒序，返回时把对方发来的未读置读）
     * <p>
     * 实现细节：
     * - GET /chat/list?peerId=&current=：需登录；peerId 为会话对象用户id，current 默认第 1 页，
     *   每页 SystemConstants.MAX_PAGE_SIZE 条。
     * - 倒序=最新在前，前端拿到后倒过来正序展示；返回时把 peer 发给我的未读批量置读（is_read=1）。
     * - 【私信会话隐藏的实现】我主动打开聊天页 = 主动恢复会话：若我仍隐藏该会话（tb_chat_hidden
     *   有记录），service 内先自动删除隐藏记录再正常返回（删除会话只是从列表移除，主动再次进入即恢复）。
     * - data=当前页消息列表（{id, fromUserId, toUserId, content, isRead, createTime}），total=总条数。
     * @param peerId  会话对象用户id
     * @param current 页码，默认第1页
     */
    @GetMapping("/list")
    public Result list(@RequestParam("peerId") Long peerId,
                       @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return chatService.list(peerId, current);
    }

    /**
     * 【私信功能的实现】查询我的会话列表（与我聊过天的用户聚合，按最后消息时间倒序）
     * <p>
     * 实现细节：
     * - GET /chat/sessions：需登录。
     * - data=List&lt;Map&gt;：每条含 peer{id,nickName,icon}（会话对方）、lastContent（最后一条消息）、
     *   lastTime（最后消息时间）、unread（对方发给我的未读数），按 lastTime 倒序，
     *   前端据此渲染会话列表（小红书式消息 Tab）。
     */
    @GetMapping("/sessions")
    public Result sessions() {
        return chatService.sessions();
    }

    /**
     * 【私信功能的实现】查询我收到的未读私信总数
     * <p>
     * 实现细节：
     * - GET /chat/unread-count：需登录；data=未读私信总数（无未读为 0），前端角标展示"小红点/数字"。
     */
    @GetMapping("/unread-count")
    public Result unreadCount() {
        return chatService.unreadCount();
    }

    /**
     * 【私信限制的实现】查询"我"与 peer 是否互相关注（互关后私信无限额）
     * <p>
     * 实现细节：
     * - GET /chat/mutual-status?peerId=：需登录；data=Map{mutual: boolean}。
     * - 判定：tb_follow 中 (me→peer) 与 (peer→me) 双向记录都存在（COUNT==2）即 mutual=true；
     *   前端在聊天页打开时调用，mutual=false 时提示"对方未关注你，今日仅可发送 3 条私信"。
     * @param peerId 会话对象用户id
     */
    @GetMapping("/mutual-status")
    public Result mutualStatus(@RequestParam("peerId") Long peerId) {
        return chatService.mutualStatus(peerId);
    }

    /**
     * 【私信会话隐藏的实现】删除"我"与 peer 的会话（微信/小红书式会话隐藏：只影响我的视图，消息永不删除）
     * <p>
     * 实现细节：
     * - DELETE /chat/session/{peerId}：需登录，/chat/** 被 LoginInterceptor 拦截，未登录 401。
     * - 会话隐藏（【私信会话隐藏的实现】）：service 内仅 INSERT tb_chat_hidden(user_id=我, peer_id=peerId)
     *   一条隐藏记录——不再删消息、不再标记 is_deleted_by_me，消息永久保留、历史完整；
     *   幂等靠唯一约束 uk_hide(user_id, peer_id)（重复删除 INSERT 冲突即忽略）。
     * - 会话恢复（两条路径）：① 对方在隐藏时间之后发来新消息 → sessions 聚合自动删除隐藏记录，
     *   会话连同完整历史重新显示；② 我主动打开聊天页（list 自动解除）或主动发消息（send 自动解除）。
     *   详见 IChatService.deleteSession 注释。
     * @param peerId 会话对象用户id
     * @return 删除成功返回 ok
     */
    @DeleteMapping("/session/{peerId}")
    public Result deleteSession(@PathVariable("peerId") Long peerId) {
        return chatService.deleteSession(peerId);
    }

    /**
     * 【会话置顶的实现】置顶"我"与 peer 的会话（会话列表前排展示，微信/小红书式置顶）
     * <p>
     * 实现细节：
     * - PUT /chat/pin/{peerId}：需登录，/chat/** 被 LoginInterceptor 拦截，未登录 401。
     * - 会话置顶（【会话置顶的实现】）：service 内仅 INSERT tb_chat_pin(user_id=我, peer_id=peerId)
     *   一条置顶记录——不碰消息、不碰隐藏表，幂等靠唯一约束 uk_pin(user_id, peer_id)
     *   （重复置顶 INSERT 冲突即忽略）；sessions 中置顶会话排最前（先置顶组后普通组，组内各自 lastTime 倒序）。
     * - 取消置顶用 DELETE /chat/pin/{peerId}（见 unpin），会话回到按 lastTime 排序的普通位置。
     * @param peerId 会话对象用户id
     * @return 置顶成功返回 ok
     */
    @PutMapping("/pin/{peerId}")
    public Result pin(@PathVariable("peerId") Long peerId) {
        return chatService.pin(peerId);
    }

    /**
     * 【会话置顶的实现】取消置顶"我"与 peer 的会话（回到按 lastTime 排序的普通位置）
     * <p>
     * 实现细节：
     * - DELETE /chat/pin/{peerId}：需登录，/chat/** 被 LoginInterceptor 拦截，未登录 401。
     * - 取消置顶（【会话置顶的实现】）：service 内 DELETE tb_chat_pin WHERE user_id=我 AND peer_id=peerId；
     *   未置顶时删除 0 行也按 ok 返回（幂等）；sessions 中该会话 isPinned=false 回到普通排序。
     * @param peerId 会话对象用户id
     * @return 取消置顶成功返回 ok
     */
    @DeleteMapping("/pin/{peerId}")
    public Result unpin(@PathVariable("peerId") Long peerId) {
        return chatService.unpin(peerId);
    }

}
