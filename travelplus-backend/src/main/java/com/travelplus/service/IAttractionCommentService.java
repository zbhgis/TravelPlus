package com.travelplus.service;

import com.travelplus.dto.Result;
import com.travelplus.entity.AttractionComment;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 【景点评论的实现】景点评论服务接口：发表评论与分页查询某景点评论列表
 * <p>
 * 实现细节：
 * - saveComment：登录用户评论，校验景点存在/content 非空，评论者 userId 取自 UserHolder（不信任前端），
 *   落库成功后不维护 tb_attraction.comments 计数（评论数由评论表 COUNT，见 impl 注释）。
 * - queryComments：按创建时间倒序分页（越新越靠前），每条经 IUserService 补作者昵称/头像
 *   （用户不存在给"匿名"兜底）。仿 TravelNoteComment 但**无回复/置顶**（景点评论简化结构）。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface IAttractionCommentService extends IService<AttractionComment> {

    /**
     * 【景点评论的实现】发表景点评论
     * <p>
     * 实现细节：需登录（/attraction-comment 在拦截器拦截范围）；评论者 userId 由服务端从 UserHolder 写入，
     * 校验 attractionId/content 非空且景点存在（不存在 fail"景点不存在"）。
     * @param comment 评论内容（attractionId/content 必填）
     * @return 发表成功返回 ok
     */
    Result saveComment(AttractionComment comment);

    /**
     * 【景点评论的实现】分页查询某景点的评论列表（按创建时间倒序，附带作者昵称/头像）
     * <p>
     * 实现细节：attraction_id 等值 + create_time 倒序分页（每页 MAX_PAGE_SIZE），每条经 IUserService
     * 补 name/icon（实体 @TableField(exist=false) 字段，仅本次响应使用），用户不存在给"匿名"兜底。
     * @param attractionId 景点id
     * @param current      页码（从1开始）
     * @return 评论列表
     */
    Result queryComments(Long attractionId, Integer current);
}
