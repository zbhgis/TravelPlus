package com.travelplus.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.travelplus.dto.Result;
import com.travelplus.entity.ChatMessage;

/**
 * 【私信功能的实现】私信服务接口：私信发送（含私信模式三档 + 陌生人回复前单条限制）、会话历史分页、会话列表、未读数统计、互关状态
 * <p>
 * 实现细节：
 * - send：先校验接收用户存在 / content 非空 ≤500，再按接收方 dm_mode 分流（关闭/仅互关/开放），
 *   开放且非互关时走"对方回复前每日 1 条"限制（回复过即解锁），通过后插入消息返回消息 id。
 * - list：以"我与 peer"双向条件分页取历史消息（create_time 倒序），返回全部历史（消息永不删除），
 *   返回时把对方发给我的未读批量置读；仍隐藏的会话自动解除隐藏（我主动打开聊天页=主动恢复会话）。
 * - sessions：与我聊过天的用户聚合会话（内存聚合，避免 N+1），排除我隐藏的 peer（tb_chat_hidden），
 *   隐藏恢复共两条路径：对方在隐藏时间之后发来新消息 → 自动解除隐藏并正常显示（会话恢复、历史完整）；
 *   或我主动打开聊天页/发消息（list/send 主动解除）。
 * - deleteSession（【私信会话隐藏的实现】）：微信/小红书式会话隐藏——只 INSERT tb_chat_hidden 隐藏记录，
 *   不删消息、不标记 is_deleted_by_me，幂等（唯一约束 uk_hide 冲突即忽略）；恢复：对方新消息自动恢复
 *   或我主动打开聊天页/发消息主动恢复。
 * - mutualStatus：是否互相关注，供前端展示"互关后才能无限私信"的提示。
 * - pin/unpin（【会话置顶的实现】）：会话置顶/取消置顶——只写 tb_chat_pin 一条置顶记录，幂等
 *   （唯一约束 uk_pin 冲突即忽略）；sessions 中置顶会话排最前（先置顶组后普通组，组内各自 lastTime 倒序）。
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
public interface IChatService extends IService<ChatMessage> {

    /**
     * 【私信功能的实现】发送一条私信（含私信模式三档限制 + 陌生人回复前每日单条）
     * <p>
     * 实现细节：
     * - 校验：toUserId 非空且不等于自己、content 非空且 ≤500、接收用户必须存在。
     * - 私信限制（【私信限制的实现】）按接收方 dm_mode 分流：=2 关闭 fail"对方已关闭私信功能"；
     *   =1 仅互关时非互关 fail"对方仅允许互关好友私信"；=0 开放时互关不限，非互关走
     *   "对方回复前每日最多 1 条"——对方回复过我则不限（回复后当天可继续对话），未回复则
     *   Redis chat:stranger:{from}:{to}:{yyyyMMdd} 计数，当日 >1 fail
     *   "对方尚未回复你，今天最多发送一条消息"。
     * - 通过后插入 is_read=0 消息；【私信会话隐藏的实现】插入成功后 DELETE tb_chat_hidden
     *   WHERE user_id=fromUserId AND peer_id=toUserId（我主动发消息 = 主动恢复会话，幂等：
     *   无隐藏记录时删除 0 行无副作用），返回 data=消息 id。
     *
     * @param toUserId 接收者用户id
     * @param content  消息内容（≤500 字）
     * @return data=新消息 id
     */
    Result send(Long toUserId, String content);

    /**
     * 【私信功能的实现】分页查询"我"与 peer 的历史私信（创建时间倒序，返回时把对方发来的未读置读）
     * <p>
     * 实现细节：
     * - 前置处理（【私信会话隐藏的实现】，主动恢复）：我打开该会话聊天页 = 主动恢复会话——若
     *   tb_chat_hidden 仍存在 user_id=我 AND peer_id=peer 记录，先 DELETE 该隐藏记录（微信语义：
     *   删除会话只是从我的列表移除，主动再次进入聊天即恢复），再继续正常查询。隐藏恢复共两条路径：
     *   ① 对方在隐藏时间后给我发新消息（sessions 聚合自动恢复）；② 我主动打开聊天页（list 自动解除）
     *   或主动发消息（send 自动解除）。
     * - 查询条件：(from_user_id=我 AND to_user_id=peer) OR (from_user_id=peer AND to_user_id=我)，
     *   按 create_time 倒序分页（每页 SystemConstants.MAX_PAGE_SIZE 条），最新在前，前端倒序展示。
     * - 历史完整（【私信会话隐藏的实现】）：返回全部历史消息，无 is_deleted_by_me 过滤——会话隐藏
     *   方案消息永不删除，恢复后从第一条到最新一条完整返回，不丢任何消息。
     * - 已读时机（【私信限制的实现】）：翻看会话即视为已读——返回前执行
     *   UPDATE tb_chat_message SET is_read=1 WHERE to_user_id=我 AND from_user_id=peer AND is_read=0，
     *   并同步把当前页中对方发给我的消息 isRead 置 1，保证返回数据与库中状态一致。
     * - data=List&lt;ChatMessage&gt;{id,fromUserId,toUserId,content,isRead,createTime}，total=总条数。
     *
     * @param peerId  会话对象用户id
     * @param current 页码，默认第1页
     * @return data=当前页消息列表，total=总条数
     */
    Result list(Long peerId, Integer current);

    /**
     * 【私信功能的实现】会话列表：与我聊过天的用户聚合，按最后消息时间倒序
     * <p>
     * 实现细节：
     * - 聚合：一条 SQL 查出所有 (from_user_id=我 OR to_user_id=我) 的消息按 create_time 倒序，
     *   内存按"对方 id"分组（LinkedHashMap 保序，先出现者=最新消息）；避免对每个会话单独查最后一条
     *   消息（N+1 问题）。
     * - 隐藏会话（【私信会话隐藏的实现】）：先查 tb_chat_hidden WHERE user_id=我，聚合循环跳过被隐藏
     *   peer（我的视角不显示该会话）。
     * - 自动解除（【私信会话隐藏的实现】）：对方在隐藏时间（tb_chat_hidden.create_time）之后给我发过
     *   新消息（from_user_id=peer AND to_user_id=我 AND create_time &gt; 隐藏时间）→ 删除该隐藏记录，
     *   会话正常显示且历史完整（隐藏期间消息从未删除，list 返回全部）。
     * - 未读数：对方（from_user_id=peer）发给我的未读（to_user_id=我 AND is_read=0）条数。
     * - 用户信息：一次 IUserService.listByIds 批量查询会话对方的 {id,nickName,icon}，无 N+1。
     * - data=List&lt;Map&gt;：{peer:{id,nickName,icon}, lastContent, lastTime, unread}，按 lastTime 倒序。
     *
     * @return data=会话列表（按最后消息时间倒序）
     */
    Result sessions();

    /**
     * 【私信功能的实现】统计我收到的未读私信总数（前端角标）
     * <p>
     * 实现细节：SELECT COUNT(*) FROM tb_chat_message WHERE to_user_id=我 AND is_read=0
     * AND NOT EXISTS (SELECT 1 FROM tb_chat_hidden h WHERE h.user_id=我 AND h.peer_id=tb_chat_message.from_user_id)
     * ——排除我隐藏会话的对方消息（【私信会话隐藏的实现】，与 sessions 隐藏语义一致）；
     * 对方新发消息后 sessions 自动解除隐藏，解除后未读数恢复统计；无未读返回 0（count 为 null 时兜底）。
     *
     * @return Result.data=未读私信总数
     */
    Result unreadCount();

    /**
     * 【私信限制的实现】判断"我"与 peer 是否互相关注
     * <p>
     * 实现细节：SELECT COUNT(*) FROM tb_follow WHERE (user_id=我 AND follow_user_id=peer)
     * OR (user_id=peer AND follow_user_id=我)，结果为 2（双向各一条，tb_follow 有
     * uk_user_follow 唯一约束，最多两条）即 mutual=true。
     *
     * @param peerId 会话对象用户id
     * @return Result.data=Map{mutual: boolean}
     */
    Result mutualStatus(Long peerId);

    /**
     * 【私信会话隐藏的实现】删除"我"与 peer 的会话（微信/小红书式会话隐藏：只影响我的视图，消息永不删除）
     * <p>
     * 实现细节（会话隐藏语义）：
     * - 删除=隐藏：仅 INSERT tb_chat_hidden(user_id=我, peer_id=peer) 一条隐藏记录——不删任何消息、
     *   不再标记 is_deleted_by_me，消息永久保留在库中，我的 list/sessions 只是不显示该会话；
     *   对方视角完全不受影响，对方会话与完整历史原样保留。
     * - 幂等：tb_chat_hidden 唯一约束 uk_hide(user_id, peer_id)，重复删除（重复隐藏）INSERT 冲突
     *   抛 DuplicateKeyException，捕获后按"已隐藏"忽略，结果仍是 ok。
     * - 自动恢复（两条路径）：① 对方在隐藏时间之后给我发新消息 → sessions 聚合时检测到新消息自动
     *   删除隐藏记录，会话连同完整历史重新显示（list 返回全部历史，不丢消息）；② 我主动打开该会话
     *   聊天页（list 自动解除）或主动给对方发消息（send 自动解除），同样恢复会话并保留完整历史。
     * - 需登录：DELETE /chat/session/{peerId} 被 LoginInterceptor 拦截，未登录返回 401。
     *
     * @param peerId 会话对象用户id
     * @return 删除成功返回 ok
     */
    Result deleteSession(Long peerId);

    /**
     * 【会话置顶的实现】置顶"我"与 peer 的会话（会话列表前排展示，只影响我的视图）
     * <p>
     * 实现细节（会话置顶语义）：
     * - 置顶=固定前排：仅 INSERT tb_chat_pin(user_id=我, peer_id=peerId) 一条置顶记录——不碰消息、
     *   不碰 tb_chat_hidden，对方视角完全不受影响；sessions 聚合后查本表把置顶会话排到最前。
     * - 幂等：tb_chat_pin 唯一约束 uk_pin(user_id, peer_id)，重复置顶 INSERT 冲突抛
     *   DuplicateKeyException，捕获后按"已置顶"忽略，结果仍是 ok。
     * - 需登录：PUT /chat/pin/{peerId} 被 LoginInterceptor 拦截，未登录返回 401。
     *
     * @param peerId 会话对象用户id
     * @return 置顶成功返回 ok
     */
    Result pin(Long peerId);

    /**
     * 【会话置顶的实现】取消置顶"我"与 peer 的会话（回到按 lastTime 排序的普通位置）
     * <p>
     * 实现细节（会话置顶语义）：
     * - 取消置顶=删除置顶记录：DELETE FROM tb_chat_pin WHERE user_id=我 AND peer_id=peerId，
     *   未置顶时删除 0 行也按 ok 返回（幂等，无副作用）；sessions 中该会话 isPinned=false 回到普通排序。
     * - 不碰消息、不碰隐藏表，只影响我的视图排序。
     * - 需登录：DELETE /chat/pin/{peerId} 被 LoginInterceptor 拦截，未登录返回 401。
     *
     * @param peerId 会话对象用户id
     * @return 取消置顶成功返回 ok
     */
    Result unpin(Long peerId);

}
