package com.travelplus.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.entity.Notify;
import com.travelplus.entity.User;
import com.travelplus.mapper.NotifyMapper;
import com.travelplus.service.INotifyService;
import com.travelplus.service.IUserService;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【通知系统的实现】通知服务实现：旁路写入、分页消息列表（补触发者信息）、未读数、已读
 * <p>
 * 实现细节：
 * - add：通知是旁路数据——主流程（点赞/关注/评论）成功后才调用，内部 try-catch 把任何异常
 *   静默消化为 log.warn，绝不回滚/打断主业务；【消息数据补真的实现】通知含自触发（self-notify）：
 *   自赞/自收藏/自评论/@自己 同样落库通知自己（用户要求消息页展示），不再排除接收者==触发者。
 * - queryNotifyList：user_id 等值 + create_time 倒序分页，每条经 IUserService.getById 补
 *   fromUser{id,nickName,icon}（非表字段 @TableField(exist=false)，用户不存在时为 null）；
 *   【通知实时昵称的实现】同时实时拼装 displayText=实时昵称+" "+纯动作 content（昵称不落库，
 *   改昵称历史通知自动显示新昵称，小红书方案）。
 * - unreadCount/read：未读计数与已读更新均限定当前登录用户，避免越权。
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@Slf4j
@Service
public class NotifyServiceImpl extends ServiceImpl<NotifyMapper, Notify> implements INotifyService {

    @Resource
    private IUserService userService;

    /**
     * 【通知系统的实现】写入一条通知（旁路，失败静默）
     * <p>
     * 实现细节：
     * - 静默策略：整个写入包在 try-catch 里，任何异常（DB 故障/参数异常）仅 log.warn，
     *   调用方（点赞/关注/评论）不受影响——通知是辅助数据，丢一条不影响主业务正确性。
     * - 【消息数据补真的实现】自触发策略：接收者==触发者（自赞/自收藏/自评论/@自己）
     *   同样落库通知——用户要求消息页展示"自赞/自收藏/自评论"，不再排除自己；
     *   仅参数非法（null id / 空 type/content）跳过，type/content 为空的脏调用直接 return。
     */
    @Override
    public void add(Long userId, Long fromUserId, String type, Long targetId, String content) {
        try {
            // 1.【消息数据补真的实现】不再排除"自己通知自己"：自赞/自收藏/自评论/@自己均落库
            //    （用户要求消息页显示自触发通知）；仅非法参数（null id、空 type/content）跳过
            if (userId == null || fromUserId == null) {
                return;
            }
            if (StrUtil.isBlank(type) || StrUtil.isBlank(content)) {
                return;
            }
            // 2.组装实体落库
            Notify notify = new Notify();
            notify.setUserId(userId);
            notify.setFromUserId(fromUserId);
            notify.setType(type);
            notify.setTargetId(targetId);
            notify.setContent(content);
            notify.setIsRead(0); // 新通知默认未读
            save(notify);
        } catch (Exception e) {
            // 3.通知失败不影响主流程：仅记录日志
            log.warn("【通知系统的实现】通知写入失败：type={}, userId={}, fromUserId={}, 原因：{}",
                    type, userId, fromUserId, e.getMessage());
        }
    }

    /**
     * 【通知系统的实现】分页查询当前登录用户的通知列表（创建时间倒序，附触发者信息）
     * <p>
     * 实现细节：兼容原无 category 调用，委托带分类版本（category=null）查询。
     */
    @Override
    public Result queryNotifyList(Integer current) {
        return queryNotifyList(current, null);
    }

    /**
     * 【消息分类的实现】分页查询当前登录用户的通知列表，支持按消息分类过滤
     * <p>
     * 实现细节：
     * - 基础查询：user_id=当前登录用户 按 create_time 倒序分页（时间越新越靠前，消息页最新在前），
     *   每页 SystemConstants.MAX_PAGE_SIZE 条，页码从 1 开始。
     * - 分类过滤（小红书式三分类，category 可选）：
     *   likes    → type IN ('like','favorite')  赞和收藏——收藏通知 type=favorite 归入此类；
     *   follows  → type IN ('follow')           新增关注；
     *   comments → type IN ('comment','reply','mention')  评论和@（一级评论/回复/@ 合并）。
     *   不传 category（null/空）不加 type 条件查全部，与原接口语义完全兼容。
     * - 触发者补充：每条通知经 IUserService.getById(fromUserId) 取触发者，昵称/头像组装为
     *   UserDTO{id,nickName,icon} 写入实体 fromUser（@TableField(exist=false) 非表字段），
     *   用户不存在（如已删号）时 fromUser 保持 null，前端判空兜底。
     * - 【通知实时昵称的实现】displayText 组装：每条通知实时拼装 displayText
     *   = fromUser.nickName + " " + content（content 只存纯动作，昵称不落库）——用户改昵称后
     *   历史通知自动显示新昵称（小红书方案：昵称展示实时、content 保持纯动作模板）；
     *   触发者不存在时 displayText 退化为纯动作 content。
     * - 返回：data=当前页通知列表，total=该用户（该分类）通知总数（Result.ok(List, total) 携带 total 供前端分页）。
     */
    @Override
    public Result queryNotifyList(Integer current, String category) {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.按创建时间倒序分页查询该用户的通知，可选按分类过滤 type 字段
        QueryWrapper<Notify> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId);
        if (StrUtil.isNotBlank(category)) {
            if ("likes".equals(category)) {
                // 2.1.赞和收藏：like + favorite（收藏通知 type=favorite 并入此类）
                wrapper.in("type", "like", "favorite");
            } else if ("follows".equals(category)) {
                // 2.2.新增关注：仅 follow
                wrapper.eq("type", "follow");
            } else if ("comments".equals(category)) {
                // 2.3.评论和@：comment（一级评论）+ reply（回复）+ mention（@提到）
                wrapper.in("type", "comment", "reply", "mention");
            } else {
                // 2.4.非法分类直接 fail
                return Result.fail("category 参数非法");
            }
        }
        wrapper.orderByDesc("create_time");
        Page<Notify> page = page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE), wrapper);
        List<Notify> records = page.getRecords();
        // 3.为每条通知补充触发者信息（昵称/头像）与展示文案（displayText 实时拼装）
        records.forEach(notify -> {
            User fromUser = userService.getById(notify.getFromUserId());
            if (fromUser == null) {
                // 触发者不存在（如已删号）：fromUser 保持 null，displayText 退化为纯动作文案，前端判空兜底
                notify.setDisplayText(notify.getContent());
                return;
            }
            UserDTO dto = new UserDTO();
            dto.setId(fromUser.getId());
            dto.setNickName(fromUser.getNickName());
            dto.setIcon(fromUser.getIcon());
            notify.setFromUser(dto);
            // 4.【通知实时昵称的实现】实时拼装展示文案：实时昵称 + " " + 纯动作 content
            //    昵称不落库（content 仅存动作模板，见 follow/favorite/like/comment/reply/mention 调用点），
            //    用户改昵称后历史通知自动显示新昵称（小红书方案：展示层实时拼装），前端渲染优先取 displayText
            String nick = fromUser.getNickName();
            notify.setDisplayText((nick != null ? nick : "") + " " + notify.getContent());
        });
        // 5.返回（total=总条数，供前端"查看更多"分页）
        return Result.ok(records, page.getTotal());
    }

    /**
     * 【通知系统的实现】统计当前登录用户的未读通知数
     * <p>
     * 实现细节：select count(*) from tb_notify where user_id=? and is_read=0；
     * count 恒非空（MySQL COUNT 无匹配返回 0），null 兜底为 0 防 NPE。
     */
    @Override
    public Result unreadCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.统计未读条数
        Long count = query().eq("user_id", userId).eq("is_read", 0).count();
        // 3.返回（count==null 兜底 0）
        return Result.ok(count == null ? 0 : count);
    }

    /**
     * 【分类未读的实现】统计当前登录用户三分类（likes/follows/comments）各自的未读通知数
     * <p>
     * 实现细节：
     * - 分类映射与 queryNotifyList 严格一致（type 字段维度），保证分类圈角标与分类列表数据同源：
     *   likes    → type IN ('like','favorite')  赞和收藏（收藏通知 type=favorite 并入此类）
     *   follows  → type IN ('follow')           新增关注
     *   comments → type IN ('comment','reply','mention')  评论和@（一级评论/回复/@ 合并）
     * - 每个分类一次 count（is_read=0 + user_id=当前登录用户），count 为 null 兜底 0；
     *   返回 LinkedHashMap 保持 likes→follows→comments 顺序，供消息页三圈依次定位角标。
     */
    @Override
    public Result categoryUnreadCount() {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.三分类各自统计未读数（映射与 queryNotifyList 严格一致；count==null 兜底 0）
        Long likes = query().eq("user_id", userId).eq("is_read", 0).in("type", "like", "favorite").count();
        Long follows = query().eq("user_id", userId).eq("is_read", 0).eq("type", "follow").count();
        Long comments = query().eq("user_id", userId).eq("is_read", 0).in("type", "comment", "reply", "mention").count();
        // 3.组装 Map 返回（LinkedHashMap 保持三分类顺序）
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("likes", likes == null ? 0 : likes);
        data.put("follows", follows == null ? 0 : follows);
        data.put("comments", comments == null ? 0 : comments);
        return Result.ok(data);
    }

    /**
     * 【通知系统的实现】标记通知已读：全部已读或单条已读
     * <p>
     * 实现细节：
     * - 全部已读（id==null）：update tb_notify set is_read=1 where user_id=? and is_read=0。
     * - 单条已读（id!=null）：update tb_notify set is_read=1 where id=? and user_id=?
     *   ——带接收者等值条件，他人通知不可被越权标记，保证"已读"语义只在本人消息上生效。
     */
    @Override
    public Result read(Long id) {
        // 1.获取当前登录用户
        Long userId = UserHolder.getUser().getId();
        if (id != null) {
            // 2.单条已读（仅本人消息可操作）
            update().set("is_read", 1).eq("id", id).eq("user_id", userId).update();
        } else {
            // 3.全部已读（本人全部未读置 1）
            update().set("is_read", 1).eq("user_id", userId).eq("is_read", 0).update();
        }
        return Result.ok();
    }
}
