package com.travelplus.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.travelplus.dto.Result;
import com.travelplus.entity.Notify;

/**
 * 【通知系统的实现】通知服务接口：写入通知、分页消息列表、未读数统计、已读操作
 * <p>
 * 实现细节：
 * - add：旁路写入——调用方（点赞/关注/评论）主流程成功后再通知，任何异常在实现内 try-catch
 *   静默消化，绝不影响主业务（通知失败只丢一条消息，不回滚点赞/关注/评论）。
 * - queryNotifyList：当前登录用户消息分页（create_time 倒序），每条补触发者 fromUser 信息。
 * - unreadCount：当前用户 is_read=0 条数。
 * - read：全部已读（id 为空）或单条已读（id 指定，仅本人消息可操作）。
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
public interface INotifyService extends IService<Notify> {

    /**
     * 【通知系统的实现】写入一条通知（旁路，失败静默不抛异常）
     * <p>
     * 实现细节：
     * - 触发时机：点赞/关注/评论/回复/@ 主流程成功后调用，不阻塞主业务。
     * - 校验：接收者与触发者相同（自赞/自评/自@/自关注）直接跳过不落库；
     *   type/content 为空同样跳过。
     * - 失败处理：try-catch 捕获后 log.warn 记录，不影响调用方结果。
     *
     * @param userId     接收者用户id
     * @param fromUserId 触发者用户id
     * @param type       通知类型 like/follow/comment/reply/mention
     * @param targetId   目标id（游记id或用户id）
     * @param content    通知文案
     */
    void add(Long userId, Long fromUserId, String type, Long targetId, String content);

    /**
     * 【通知系统的实现】分页查询当前登录用户的通知列表（创建时间倒序，附触发者信息）
     * <p>
     * 实现细节：按 user_id=当前用户 + create_time 倒序分页（每页 SystemConstants.MAX_PAGE_SIZE 条），
     * 每条经 IUserService.getById(fromUserId) 组装 fromUser{id,nickName,icon} 写入实体
     * 非表字段（取不到给空，前端兜底展示）。兼容不传 category 的原调用。
     *
     * @param current 页码，默认第1页
     * @return data=当前页通知列表（含 fromUser），total=总条数
     */
    Result queryNotifyList(Integer current);

    /**
     * 【消息分类的实现】分页查询当前登录用户的通知列表，支持按消息分类过滤（小红书式三分类）
     * <p>
     * 实现细节：
     * - category 可选：不传（null/空）查询全部，语义与原 queryNotifyList(current) 完全一致。
     * - 三分类映射（type 字段维度）：
     *   likes    → type IN ('like','favorite')  赞和收藏（收藏通知 type=favorite 并入此类）
     *   follows  → type IN ('follow')           新增关注
     *   comments → type IN ('comment','reply','mention')  评论和@（一级评论/回复/@ 合并）
     * - 非法 category 直接 fail"category 参数非法"，其余逻辑（倒序分页 + fromUser 补充）与原列表一致。
     *
     * @param current  页码，默认第1页
     * @param category 分类：likes=赞和收藏 / follows=新增关注 / comments=评论和@；可为空（查全部）
     * @return data=当前页通知列表（含 fromUser），total=总条数
     */
    Result queryNotifyList(Integer current, String category);

    /**
     * 【通知系统的实现】统计当前登录用户的未读通知数
     * <p>
     * 实现细节：select count(*) from tb_notify where user_id=? and is_read=0，
     * 无未读返回 0（count 为 null 时兜底）。
     *
     * @return Result.data=未读数
     */
    Result unreadCount();

    /**
     * 【分类未读的实现】统计当前登录用户三分类（likes/follows/comments）各自的未读通知数
     * <p>
     * 实现细节：
     * - 分类映射与 queryNotifyList 严格一致（type 字段维度）：
     *   likes    → type IN ('like','favorite')  赞和收藏（收藏通知 type=favorite 并入此类）
     *   follows  → type IN ('follow')           新增关注
     *   comments → type IN ('comment','reply','mention')  评论和@（一级评论/回复/@ 合并）
     * - 每个分类一次 count（is_read=0 + user_id=当前登录用户），count 为 null 兜底 0。
     *
     * @return Result.data=Map{likes: n, follows: n, comments: n}，消息页三分类圈角标使用
     */
    Result categoryUnreadCount();

    /**
     * 【通知系统的实现】标记通知已读：全部已读或单条已读
     * <p>
     * 实现细节：id 为空 → 当前用户全部未读置 1；id 非空 → 仅当该通知属于当前用户时置 1
     * （带 user_id 等值条件，越权操作他人通知不生效，安全兜底）。
     *
     * @param id 通知id，可为空（空=全部已读）
     * @return 操作成功返回 ok
     */
    Result read(Long id);

}
