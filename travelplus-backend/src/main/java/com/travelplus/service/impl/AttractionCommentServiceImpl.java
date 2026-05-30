package com.travelplus.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelplus.dto.Result;
import com.travelplus.entity.AttractionComment;
import com.travelplus.entity.User;
import com.travelplus.mapper.AttractionCommentMapper;
import com.travelplus.service.IAttractionCommentService;
import com.travelplus.service.IAttractionService;
import com.travelplus.service.IUserService;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 【景点评论的实现】景点评论服务实现：发表评论、分页查询评论列表
 * <p>
 * 实现细节：
 * - saveComment：评论者 userId 取自 UserHolder（需登录，/attraction-comment 不在拦截器 exclude 列表），
 *   校验 attractionId/content 非空（fail"评论参数不完整"）且景点存在（fail"景点不存在"）后落库。
 *   **不维护 tb_attraction.comments 计数**——景点评论数由评论表 COUNT 实时统计
 *   （见 AttractionServiceImpl.queryById 的 attachCommentCount），避免评论/删评时计数与表数据不一致，
 *   与游记评论（同步 comments ±1）策略不同，此处为简化设计（注释于接口与实现）。
 * - queryComments：attraction_id 等值 + create_time 倒序分页（每页 SystemConstants.MAX_PAGE_SIZE 条，
 *   页码从 1 开始），每条经 IUserService.getById 取评论作者补昵称/头像（非表字段 name/icon），
 *   用户不存在（如账号已删除）时 name 置"匿名"、icon 保持 null，评论列表仍可正常渲染。
 * - 结构简化：仿 TravelNoteComment 但**无回复/置顶**——景点评论仅一级平铺结构，无 parentId/answerId/
 *   is_top 字段，前端直接按时间倒序渲染。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Slf4j
@Service
public class AttractionCommentServiceImpl extends ServiceImpl<AttractionCommentMapper, AttractionComment> implements IAttractionCommentService {

    @Resource
    private IUserService userService;

    /** 【景点评论的实现】景点存在性校验用（直连 Service，AttractionServiceImpl 不依赖评论服务，无循环依赖） */
    @Resource
    private IAttractionService attractionService;

    /**
     * 【景点评论的实现】发表景点评论
     * <p>
     * 实现细节：
     * - 需登录：评论者 userId 从 UserHolder.getUser().getId() 获取并覆盖实体字段，
     *   前端请求必须带有效 token（/attraction-comment 不在拦截器 exclude 列表），未登录返回 401。
     * - 参数校验：attractionId/content 任一为空 fail "评论参数不完整"；景点经 attractionService.getById
     *   校验存在，不存在 fail "景点不存在"（防对无效景点发表评论）。
     * - 落库：save 成功后直接返回 ok；不更新 tb_attraction.comments 计数（由评论表 COUNT 兜底，见类注释）。
     */
    @Override
    public Result saveComment(AttractionComment comment) {
        // 1.获取登录用户id（拦截器保证非空）
        Long userId = UserHolder.getUser().getId();
        comment.setUserId(userId);
        // 2.校验参数：attractionId/content 必填
        if (comment.getAttractionId() == null || StrUtil.isBlank(comment.getContent())) {
            return Result.fail("评论参数不完整");
        }
        // 3.校验景点存在（不存在则不接受该评论）
        if (attractionService.getById(comment.getAttractionId()) == null) {
            return Result.fail("景点不存在");
        }
        // 4.插入评论
        boolean isSuccess = save(comment);
        if (!isSuccess) {
            return Result.fail("发表评论失败");
        }
        // 5.返回（不维护 tb_attraction.comments 计数，评论数由评论表 COUNT）
        return Result.ok();
    }

    /**
     * 【景点评论的实现】分页查询某景点的评论列表
     * <p>
     * 实现细节：
     * - 查询：attraction_id = attractionId 按 create_time 倒序分页（时间越新越靠前），
     *   每页 SystemConstants.MAX_PAGE_SIZE 条，页码从 1 开始。
     * - 用户信息补充：每条评论经 IUserService.getById(comment.getUserId()) 取用户，
     *   昵称/头像写入实体上 @TableField(exist=false) 的 name/icon 字段（复用游记评论补用户信息模式，
     *   不落库，仅本次响应使用）。
     * - 兜底策略：用户不存在时 name 置"匿名"、icon 保持 null，评论列表仍可正常渲染。
     */
    @Override
    public Result queryComments(Long attractionId, Integer current) {
        // 1.分页查询该景点的评论（创建时间倒序）
        Page<AttractionComment> page = query()
                .eq("attraction_id", attractionId)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<AttractionComment> records = page.getRecords();
        // 2.为每条评论补充用户昵称与头像
        records.forEach(comment -> {
            User user = userService.getById(comment.getUserId());
            if (user == null) {
                comment.setName("匿名");
                return;
            }
            comment.setName(user.getNickName());
            comment.setIcon(user.getIcon());
        });
        // 3.返回
        return Result.ok(records);
    }
}
