package com.travelplus.service;

import com.travelplus.dto.Result;
import com.travelplus.entity.Follow;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 【关注功能的实现】关注服务接口：关注/取关、是否已关注、共同关注、关注/粉丝列表（含隐私校验）
 * <p>
 * 实现细节：
 * - 关注/取关同步维护 DB tb_follow 与 Redis Set follows:{userId}（followCommons 依赖集合交集）。
 * - queryFollowList 支持 following/fans 双向分页，列表可见性受 tb_user_info 隐私开关控制。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 【关注功能的实现】关注/取关用户（幂等，DB 与 Redis 同步）
     * <p>
     * 实现细节：关注前先查重避免重复 insert 触发唯一约束 uk_user_follow，Redis SADD 幂等补成员；
     * 取关按 (user_id, follow_user_id) 条件删除并 SREM 集合成员（天然幂等）。
     * @param followUserId 被关注/取关的用户id
     * @param isFollow     true=关注，false=取关
     * @return 操作成功返回 ok
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 【关注功能的实现】判断当前登录用户是否已关注指定用户
     * <p>
     * 实现细节：COUNT(tb_follow WHERE user_id=当前用户 AND follow_user_id=?)，>0 即已关注。
     * @param followUserId 被查询用户id
     * @return Result.data=boolean 是否已关注
     */
    Result isFollow(Long followUserId);

    /**
     * 【关注功能的实现】查询当前用户与指定用户的共同关注
     * <p>
     * 实现细节：Redis Set follows:{当前用户} ∩ follows:{目标用户} 求交集（依赖关注/取关时的集合同步），
     * 解析 userId 后 IN 查用户转 UserDTO 返回。
     * @param id 目标用户id
     * @return 共同关注用户列表（无则空列表）
     */
    Result followCommons(Long id);

    /**
     * 【关注粉丝列表的实现】分页查询某用户的关注列表（following）或粉丝列表（fans），含隐私校验
     * <p>
     * 实现细节：type 决定查询方向（following=user_id=id，fans=follow_user_id=id）；先查被查看用户
     * tb_user_info 的 follow_visible/fans_visible，隐藏且访问者非本人时 fail（隐私设置）；
     * tb_follow 按 id 倒序分页（每页 MAX_PAGE_SIZE），IN 查用户 + ORDER BY FIELD 保持顺序，
     * 一次 Set 查询附加 isFollowed（避免 N+1）。
     * @param id      被查看用户id
     * @param type    列表类型：following=关注列表，fans=粉丝列表（必填）
     * @param current 页码（默认1）
     * @return Result.data=List&lt;UserDTO&gt;（含 isFollowed），total=关系总数
     */
    Result queryFollowList(Long id, String type, Integer current);
}
