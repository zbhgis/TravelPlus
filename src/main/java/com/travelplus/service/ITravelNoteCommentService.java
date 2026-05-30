package com.travelplus.service;

import com.travelplus.dto.Result;
import com.travelplus.entity.TravelNoteComment;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 【评论功能的实现】游记评论服务接口：发表评论与分页查询评论列表
 * <p>
 * 实现细节：
 * - saveComment：登录用户评论，校验 noteId/content，补齐默认字段（parentId/answerId/liked/status），
 *   落库成功后同步 tb_travel_note.comments + 1。
 * - queryComments：按创建时间升序分页，每条经 IUserService 补昵称/头像（查不到给"匿名"）。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface ITravelNoteCommentService extends IService<TravelNoteComment> {

    /**
     * 【评论功能】发表评论
     * @param comment 评论内容（noteId/content 必填，parentId/answerId 可选）
     * @return 发表成功返回 ok
     */
    Result saveComment(TravelNoteComment comment);

    /**
     * 【评论功能】分页查询某游记的评论列表（按创建时间升序，附带用户昵称/头像）
     * @param noteId 游记id
     * @param current 页码
     * @return 评论列表
     */
    Result queryComments(Long noteId, Integer current);

}
