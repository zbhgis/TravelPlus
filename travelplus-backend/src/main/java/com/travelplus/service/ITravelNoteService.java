package com.travelplus.service;

import com.travelplus.dto.Result;
import com.travelplus.entity.TravelNote;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 【游记功能的实现】游记服务接口：发布（Feed 推流）、点赞、热门/详情/点赞用户查询、关注 Feed 实时分页、删除
 * <p>
 * 实现细节：
 * - 发布成功把 noteId 推入作者所有粉丝的 Feed（Redis ZSet，key=feed:{粉丝id}，score=时间戳）。
 *   【关注Feed的实现】关注流读取已改为实时查询（queryTravelNoteOfFollow 按"关注列表+可见性"实时
 *   join，不再消费 feed 收件箱）；推流写入保留为历史实现，不影响实时读取结果。
 * - 点赞态以 Redis ZSet note:liked:{noteId} 为准（成员=userId），DB liked 计数与集合同步。
 * - 详情查询对登录用户写浏览记录（member=note:{id}）。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface ITravelNoteService extends IService<TravelNote> {

    /**
     * 【游记功能的实现】热门游记分页查询（按点赞数倒序）
     * <p>
     * 实现细节：DB 按 liked 倒序分页（每页 MAX_PAGE_SIZE），逐条补作者昵称/头像
     * （queryTravelNoteUser）与当前登录用户点赞态（isTravelNoteLiked）。
     * @param current 页码（从1开始）
     * @return 热门游记列表（含 name/icon/isLike）
     */
    Result queryHotTravelNote(Integer current);

    /**
     * 【浏览记录的实现】游记详情查询，登录用户浏览时写入浏览记录
     * <p>
     * 实现细节：getById 查详情（不存在 fail），成功后对已登录用户调用
     * browseRecordService.record(userId, "note", id)（ZSet member=note:{id}），
     * 再补作者信息与点赞态、收藏态后返回；若 note.attractionId 非空则 join 关联景点
     * {id,name,image} 写入 attraction 字段（【游记关联景点的实现】）。
     * @param id 游记id
     * @return 游记详情（含 name/icon/isLike/attraction）；不存在 fail "游记不存在！"
     */
    Result queryTravelNoteById(Long id);

    /**
     * 【游记点赞的实现】点赞/取消点赞切换
     * <p>
     * 实现细节：以 Redis ZSet note:liked:{id} 中是否含当前用户判断状态——未点赞则 DB liked+1 且
     * ZADD 用户（score=时间戳，Top5 排序依据）；已点赞则 DB liked-1 且 ZREM 用户；DB 更新成功才动 Redis。
     * @param id 游记id
     * @return 切换成功返回 ok
     */
    Result likeTravelNote(Long id);

    /**
     * 【游记点赞的实现】查询游记点赞数 Top5 用户
     * <p>
     * 实现细节：ZRANGE note:liked:{id} 0 4 取点赞时间最早的 5 个 userId，IN 查用户并用
     * ORDER BY FIELD 保持点赞顺序，转 UserDTO 返回。
     * @param id 游记id
     * @return Top5 点赞用户列表（无则空列表）
     */
    Result queryTravelNoteLikes(Long id);

    /**
     * 【游记功能的实现】发布游记并推送给作者粉丝
     * <p>
     * 实现细节：note.userId 取自 UserHolder（不信任前端）；【笔记可见性的实现】note.visible
     * 支持请求体传入（null 默认 0=公开，仅 0/1 合法）；save 落库失败 fail；
     * 【游记关联景点的实现】note.attractionId 为 null 不关联（可选），非 null 时校验景点存在，
     * 不存在 fail"关联的景点不存在"；成功后查作者全部粉丝（tb_follow.follow_user_id=作者），
     * 把 noteId 以 score=当前时间戳 ZADD 进每个粉丝的 Feed（key=feed:{粉丝id}），
     * 供 queryTravelNoteOfFollow 拉取（拉取时过滤他人隐藏笔记）。
     * @param note 游记（title/images/content/attractionId?/visible?）
     * @return Result.data=noteId
     */
    Result saveTravelNote(TravelNote note);

    /**
     * 【笔记编辑的实现】编辑已发布笔记（仅作者本人可编辑，非空字段更新）
     * <p>
     * 实现细节：
     * - 需登录：PUT /note/{id} 在 LoginInterceptor 拦截范围内。
     * - 作者校验：以 UserHolder 为准，笔记作者 != 当前登录用户 fail"无权编辑该游记"；
     *   笔记不存在 fail"游记不存在"。
     * - 非空更新：title/content/images/attractionId/visible 任一提交才更新（null=不修改保持原值）；
     *   visible 仅 0/1；attractionId 非空时校验景点存在。
     * @param id   待编辑的游记id
     * @param note 编辑入参（title/content/images/attractionId/visible 均可选）
     * @return 编辑成功返回 ok
     */
    Result updateNote(Long id, TravelNote note);

    /**
     * 【关注Feed的实现】关注流实时查询分页（我关注的所有人发布的可见游记，按发布时间倒序）
     * <p>
     * 实现细节：
     * - 实时 join：先查 tb_follow WHERE user_id=当前用户 拿到关注对象集合（follow_user_id），
     *   再查这些用户发布的可见游记（visible=0，作者本人始终可见），按 create_time 倒序分页
     *   （每页 SystemConstants.MAX_PAGE_SIZE），避免推送制依赖发布时点导致的历史关注者游记缺失。
     * - 补全：复用 queryTravelNoteUser（作者 name/icon）与 isTravelNoteLiked（当前用户点赞态）。
     * - 数据一致性：返回与我关注的人实时一致——关注列表变化、游记发布/隐藏/删除均即时反映。
     * @param current 页码（从1开始）
     * @return 当前页关注游记列表（含 name/icon/isLike），total=符合条件的总数
     */
    Result queryTravelNoteOfFollow(Integer current);

    /**
     * 【游记删除的实现】删除游记并清理关联的 Redis 缓存
     * <p>
     * 实现细节：作者校验（note.userId != 当前登录用户 fail，重复删除走"游记不存在"幂等）；
     * DB removeById 物理删除；Redis 删除点赞集合 note:liked:{id} 并从作者自身 Feed 移除该 noteId
     * （粉丝 feed 的失效 noteId 拉取时过滤，演示简化）。
     * @param id 游记id
     * @return 删除成功返回 ok
     */
    Result deleteNote(Long id);

}
