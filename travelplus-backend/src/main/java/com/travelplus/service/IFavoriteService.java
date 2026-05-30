package com.travelplus.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.travelplus.dto.Result;
import com.travelplus.entity.Favorite;

/**
 * 【收藏功能的实现】收藏服务接口：收藏/取消收藏（游记/景点）、收藏列表（join 目标标题与首图）、收藏状态、收藏数
 * <p>
 * 实现细节：
 * - favorite：校验目标类型（note/attraction）与目标存在后 INSERT；唯一约束 uk_user_target 冲突=已收藏，
 *   幂等返回 ok；首次收藏成功后旁路通知目标作者（note 查 tb_travel_note.user_id，attraction 无作者跳过），
 *   通知 type=favorite（归入"赞和收藏"消息分类，见 NotifyController 分类过滤注释）。
 * - unfavorite：按 (user_id, target_type, target_id) 条件删除，天然幂等。
 * - list：某用户某类型收藏分页（create_time 倒序），join 游记/景点表取标题与 images 首图，目标已删除跳过；
 *   userId 缺省=当前登录用户（本人收藏页），传入=查看该用户收藏（他人主页展示），用户不存在 fail。
 * - status：当前用户是否已收藏；favoriteCount：目标收藏总数（供详情页展示）。
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
public interface IFavoriteService extends IService<Favorite> {

    /**
     * 【收藏功能的实现】收藏游记/景点（幂等）
     * <p>
     * 实现细节：
     * - 校验：targetType ∈ {note, attraction}（非法 fail），目标必须存在（不存在 fail"收藏的目标不存在"）。
     * - 幂等：直接 INSERT，唯一键冲突（DuplicateKeyException）=已收藏，捕获后返回 ok——
     *   前端对同一目标重复点击收藏不会报错也不会产生重复行/重复通知。
     * - 通知：仅首次收藏成功（INSERT 成功）后旁路通知目标作者——note 查 tb_travel_note.user_id，
     *   attraction 表无作者字段直接跳过；失败静默不影响收藏结果。
     *
     * @param targetType 目标类型：note=游记 / attraction=景点
     * @param targetId   目标id（游记id或景点id）
     * @return 收藏成功返回 ok（重复收藏同样 ok）
     */
    Result favorite(String targetType, Long targetId);

    /**
     * 【收藏功能的实现】取消收藏游记/景点（幂等）
     * <p>
     * 实现细节：按 (user_id, target_type, target_id) 条件删除，未收藏时删 0 行同样返回 ok。
     *
     * @param targetType 目标类型：note=游记 / attraction=景点
     * @param targetId   目标id（游记id或景点id）
     * @return 取消成功返回 ok
     */
    Result unfavorite(String targetType, Long targetId);

    /**
     * 【收藏功能的实现】某用户某类型收藏列表（分页，join 目标标题与首图；默认当前登录用户）
     * <p>
     * 实现细节：按 user_id + target_type 分页查收藏关系（create_time 倒序，每页 SystemConstants.MAX_PAGE_SIZE 条），
     * 再逐条 getById 目标取标题/名称与 images 首图（逗号分隔取第 1 张），目标已删除则跳过该条。
     * <p>
     * 主页展示他人收藏：userId 缺省（null）=当前登录用户（本人"我的收藏"页）；userId 非空=查看该用户
     * 的收藏（他人主页收藏 tab），此时需校验该用户存在（getById 查不到 fail"用户不存在"）。
     *
     * @param type    收藏类型：note=游记收藏页 / attraction=景点收藏页（必填）
     * @param current 页码，默认第1页
     * @param userId  收藏归属用户id（可选；null=当前登录用户，非空=查看该用户收藏）
     * @return data=List<Map{id, targetType, targetId, title, image, createTime}>，total=收藏总数
     */
    Result list(String type, Integer current, Long userId);

    /**
     * 【收藏功能的实现】当前用户是否已收藏该目标（详情页收藏按钮回显）
     * <p>
     * 实现细节：COUNT(tb_favorite WHERE user_id=当前用户 AND target_type=? AND target_id=?) > 0，
     * 与收藏/取消收藏实时落库保持一致。
     *
     * @param targetType 目标类型：note=游记 / attraction=景点
     * @param targetId   目标id
     * @return Result.data=boolean 是否已收藏
     */
    Result status(String targetType, Long targetId);

    /**
     * 【收藏功能的实现】某目标的收藏数（详情页收藏数展示，公开）
     * <p>
     * 实现细节：COUNT(tb_favorite WHERE target_type=? AND target_id=?) 实时统计，
     * 收藏走 DB 落库无缓存，count 为 null 时兜底 0。
     *
     * @param targetType 目标类型：note=游记 / attraction=景点
     * @param targetId   目标id
     * @return Result.data=收藏数
     */
    Result favoriteCount(String targetType, Long targetId);

}
