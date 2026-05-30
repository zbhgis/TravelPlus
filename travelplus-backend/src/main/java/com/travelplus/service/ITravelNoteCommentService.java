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

    /**
     * 【评论删除的实现】删除评论（评论作者或该游记作者可删）
     * <p>
     * 实现细节：评论作者（comment.userId==当前用户）或游记作者（note.userId==当前用户）
     * 有权删除；删除该评论及其直接回复（parent_id=id），并同步游记 comments 计数
     * -（1+被删回复数），见 TravelNoteCommentServiceImpl.deleteComment。
     * @param id 评论id
     * @return 删除成功返回 ok；无权限 fail"无权删除"
     */
    Result deleteComment(Long id);

    /**
     * 【评论置顶的实现】置顶/取消置顶一级评论（仅游记作者可操作）
     * <p>
     * 实现细节：仅一级评论（parent_id=0）可置顶；同一游记最多置顶 3 条，
     * 超限时取消最旧的置顶再置顶新的，见 TravelNoteCommentServiceImpl.topComment。
     * @param id  评论id
     * @param top true=置顶，false=取消置顶
     * @return 操作成功返回 ok；无权限 fail"无权操作"
     */
    Result topComment(Long id, Boolean top);

}
