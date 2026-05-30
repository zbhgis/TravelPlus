package com.travelplus.controller;


import com.travelplus.dto.Result;
import com.travelplus.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * 【关注模块接口的实现】关注关系统一入口：关注/取关、关注状态查询、共同关注、关注/粉丝列表
 * <p>
 * 实现细节：
 * - 路由域：@RequestMapping("/follow")，挂 /follow/** 前缀。
 * - 需登录：/follow/** 在 LoginInterceptor 拦截范围内（不在 exclude 列表），未登录返回 401；
 *   当前用户 id 由服务端从 UserHolder 获取。
 * - 关注关系双写：DB（tb_follow）+ Redis 集合（follows:{userId}），DB 保证持久化与计数一致，
 *   Redis 集合供共同关注求交集、避免 N+1 查询，见 service 实现注释。
 *
 * @author TravelPlus
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /**
     * 【关注功能的实现】关注 / 取消关注用户（DB + Redis 集合双写）
     * <p>
     * 实现细节：
     * - 需登录：PUT /follow/{id}/{true|false} 被 LoginInterceptor 拦截，未登录返回 401。
     * - 关注（isFollow=true）：DB 按 (user_id, follow_user_id) 判重后幂等新增（防重复 insert 触发
     *   唯一约束），同时 SADD 到 Redis follows:{userId}；取关（isFollow=false）：DB 删除关系
     *   成功后再 SREM 移除 Redis 成员，两处保持同步。
     * - 关注后粉丝的 Feed 收件箱由发布游记时被动推送（见 TravelNoteServiceImpl.saveTravelNote），
     *   关注动作本身不搬历史数据。
     * @param followUserId 被关注/取关的用户id
     * @param isFollow     true=关注，false=取关
     * @return 操作成功返回 ok
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    /**
     * 【关注状态查询的实现】查询当前登录用户是否已关注某用户
     * <p>
     * 实现细节：
     * - 需登录：GET /follow/or/not/{id} 被 LoginInterceptor 拦截。
     * - 查 DB tb_follow 中 (user_id=当前用户, follow_user_id=id) 是否存在，
     *   data=Boolean（true=已关注，false=未关注），用于主页"关注/已关注"按钮回显。
     * @param followUserId 目标用户id
     * @return data=boolean
     */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    /**
     * 【共同关注的实现】查询当前登录用户与目标用户的共同关注列表
     * <p>
     * 实现细节：
     * - 需登录：GET /follow/common/{id} 被 LoginInterceptor 拦截。
     * - 对 Redis 关注集合 follows:{当前用户} 与 follows:{id} 做交集（SINTER），
     *   一次 O(N) 运算拿到共同关注 id 集，避免对每个关注逐个比对（DB 查询无法高效做交集）。
     * - 无交集时返回空列表；有交集则按 id 查 tb_user 转 UserDTO 返回，供"你们共同关注了"展示。
     * @param id 目标用户id
     * @return data=共同关注的 UserDTO 列表
     */
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id){
        return followService.followCommons(id);
    }

    /**
     * 【关注粉丝列表的实现】分页查询某用户的关注列表或粉丝列表
     * <p>
     * 实现细节：
     * - GET /follow/list/{id}?type=following|fans&current=页码：type 必填（following=关注，fans=粉丝），
     *   current 默认第 1 页，每页 SystemConstants.MAX_PAGE_SIZE 条。
     * - 返回 data 为 List<UserDTO>（每条附 isFollowed=当前登录用户是否已关注），total 为关注关系总数。
     * - 需登录：/follow/** 在 LoginInterceptor 拦截范围内，未登录返回 401。
     * @param id      被查看的用户id
     * @param type    列表类型：following=关注列表，fans=粉丝列表
     * @param current 页码，默认第1页
     */
    @GetMapping("/list/{id}")
    public Result queryFollowList(@PathVariable("id") Long id,
                                  @RequestParam("type") String type,
                                  @RequestParam(value = "current", defaultValue = "1") Integer current){
        return followService.queryFollowList(id, type, current);
    }
}
