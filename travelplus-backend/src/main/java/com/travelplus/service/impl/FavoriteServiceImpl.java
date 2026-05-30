package com.travelplus.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.Result;
import com.travelplus.entity.Attraction;
import com.travelplus.entity.Favorite;
import com.travelplus.entity.TravelNote;
import com.travelplus.entity.UserInfo;
import com.travelplus.mapper.FavoriteMapper;
import com.travelplus.mapper.UserInfoMapper;
import com.travelplus.mapper.UserMapper;
import com.travelplus.service.IAttractionService;
import com.travelplus.service.IFavoriteService;
import com.travelplus.service.INotifyService;
import com.travelplus.service.ITravelNoteService;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【收藏功能的实现】收藏服务实现：收藏/取消收藏（唯一约束幂等 + 通知目标作者）、收藏列表 join、状态与计数
 * <p>
 * 实现细节：
 * - 目标类型白名单：{note, attraction}，非法类型直接 fail（与前端收藏按钮入参对齐）。
 * - 目标存在性校验：note 查 tb_travel_note、attraction 查 tb_attraction，目标不存在 fail（收藏无效目标无意义）。
 * - 收藏幂等：tb_favorite 唯一约束 uk_user_target(user_id, target_type, target_id)——直接 INSERT，
 *   唯一键冲突（DuplicateKeyException）=已收藏，捕获后返回 ok，避免重复收藏报错或产生重复行/重复通知。
     * - 通知目标作者：仅首次收藏成功（INSERT 成功）后发 type=favorite 通知——note 查作者 tb_travel_note.user_id，
     *   attraction 表无作者字段直接跳过；通知旁路 try-catch 静默（与点赞/关注通知同策略）。
     *   【消息数据补真的实现】自收藏也通知自己：收藏自己的游记（收藏者==作者）同样落库 favorite
     *   通知（NotifyServiceImpl.add 不再排除接收者==触发者，用户要求消息页显示自收藏）。
 * - 收藏列表 join：先按 user_id+target_type 分页查收藏关系（create_time 倒序），再逐条 getById 目标
 *   取标题/名称与 images 首图（逗号分隔取第 1 张），目标已删除则跳过该条。
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@Slf4j
@Service
public class FavoriteServiceImpl extends ServiceImpl<FavoriteMapper, Favorite> implements IFavoriteService {

    /** 【收藏功能的实现】收藏目标类型白名单：note=游记 / attraction=景点 */
    private static final Set<String> TARGET_TYPES = new HashSet<>(Arrays.asList("note", "attraction"));

    @Resource
    private ITravelNoteService travelNoteService;

    @Resource
    private IAttractionService attractionService;

    @Resource
    private INotifyService notifyService;

    @Resource
    private UserMapper userMapper;

    /** 【隐私设置的实现】收藏列表可见性校验用（读 tb_user_info.favorites_visible，直查 Mapper 避免循环依赖） */
    @Resource
    private UserInfoMapper userInfoMapper;

    /**
     * 【收藏功能的实现】收藏游记/景点（幂等）
     * <p>
     * 实现细节：
     * - 校验：targetType ∈ {note, attraction} 且 targetId 非空，非法直接 fail；
     *   目标存在性校验（targetExists）防止收藏已删除的游记/景点。
     * - 幂等：直接 INSERT，唯一约束 uk_user_target 冲突（DuplicateKeyException）=已收藏，
     *   捕获后返回 ok——重复点击收藏不报错、不重复落库、不重复发通知。
     * - 通知：仅 INSERT 成功（首次收藏）才 notifyTargetAuthor（type=favorite），
     *   见 notifyTargetAuthor 注释；/favorite/** 需登录，UserHolder 非空。
     *
     * @param targetType 目标类型：note=游记 / attraction=景点
     * @param targetId   目标id
     * @return 收藏成功返回 ok（重复收藏同样返回 ok）
     */
    @Override
    public Result favorite(String targetType, Long targetId) {
        // 1.参数校验：目标类型白名单 + 目标id非空
        if (StrUtil.isBlank(targetType) || !TARGET_TYPES.contains(targetType) || targetId == null) {
            return Result.fail("targetType 必须为 note 或 attraction，且 targetId 不能为空");
        }
        // 2.目标存在性校验：收藏不存在的游记/景点无意义
        if (!targetExists(targetType, targetId)) {
            return Result.fail("收藏的目标不存在");
        }
        // 3.获取当前登录用户（/favorite/** 被 LoginInterceptor 拦截，UserHolder 非空）
        Long userId = UserHolder.getUser().getId();
        // 4.INSERT：唯一约束 uk_user_target 冲突=已收藏，幂等返回 ok
        Favorite favorite = new Favorite();
        favorite.setUserId(userId);
        favorite.setTargetType(targetType);
        favorite.setTargetId(targetId);
        boolean saved;
        try {
            saved = save(favorite);
        } catch (DuplicateKeyException e) {
            // 4.1.唯一键冲突：同一用户已收藏同一目标，幂等返回 ok（不重复通知、不报错）
            return Result.ok();
        }
        if (!saved) {
            return Result.fail("收藏失败");
        }
        // 5.首次收藏成功后才通知目标作者（note 有作者；attraction 无作者跳过）
        notifyTargetAuthor(targetType, targetId, userId);
        return Result.ok();
    }

    /**
     * 【收藏功能的实现】取消收藏游记/景点（幂等）
     * <p>
     * 实现细节：按 (user_id, target_type, target_id) 条件删除，未收藏时删 0 行同样成功，
     * 无需先查后删；取消收藏不发通知（与点赞取消一致，避免消息打扰）。
     *
     * @param targetType 目标类型：note=游记 / attraction=景点
     * @param targetId   目标id
     * @return 取消成功返回 ok
     */
    @Override
    public Result unfavorite(String targetType, Long targetId) {
        // 1.参数校验
        if (StrUtil.isBlank(targetType) || !TARGET_TYPES.contains(targetType) || targetId == null) {
            return Result.fail("targetType 必须为 note 或 attraction，且 targetId 不能为空");
        }
        // 2.获取当前登录用户，条件删除（天然幂等）
        Long userId = UserHolder.getUser().getId();
        remove(new QueryWrapper<Favorite>()
                .eq("user_id", userId)
                .eq("target_type", targetType)
                .eq("target_id", targetId));
        return Result.ok();
    }

    /**
     * 【收藏功能的实现】某用户某类型收藏列表（分页，join 目标标题与首图；默认当前登录用户，他人主页展示）
     * <p>
     * 实现细节：
 * - 归属用户：userId 缺省（null）=当前登录用户（本人"我的收藏"页，/favorite/** 需登录，
 *   UserHolder 非空）；userId 非空=查看该用户收藏（他人主页收藏 tab），此时 getById 校验用户存在，
 *   不存在 fail "用户不存在"。
 * - 隐私（【隐私设置的实现】）：查看他人收藏时校验 tb_user_info.favorites_visible——0=不可见且
 *   非本人 → fail "对方设置了隐私，该内容不可见"；UserInfo 不存在=默认公开；本人显式传自己 id 也可见。
     * - 分页：按 user_id + target_type 等值查询，create_time 倒序（收藏时间越新越靠前），
     *   每页 SystemConstants.MAX_PAGE_SIZE 条。
     * - join：逐条按 target_id 查游记（title/images）或景点（name/images），取标题与 images 首图
     *   （逗号分隔第 1 张，见 firstImage）封装 Map{id, targetType, targetId, title, image, createTime}；
     *   目标已被删除时该条跳过（buildFavoriteItem 返回 null），避免返回残缺数据。
     * - 返回：data=当前页 Map 列表，total=收藏关系总数（供前端分页）。
     *
     * @param type    收藏类型：note=游记收藏页 / attraction=景点收藏页（必填）
     * @param current 页码，默认第1页
     * @param userId  收藏归属用户id（可选；null=当前登录用户，非空=查看该用户收藏）
     * @return data=List<Map>，total=总数
     */
    @Override
    public Result list(String type, Integer current, Long userId) {
        // 1.校验类型参数（note=游记收藏页 / attraction=景点收藏页）
        if (StrUtil.isBlank(type) || !TARGET_TYPES.contains(type)) {
            return Result.fail("type 参数必须为 note 或 attraction");
        }
        // 2.确定收藏归属用户：userId 缺省=当前登录用户（本人收藏页）；非空=查看该用户收藏（他人主页展示）
        if (userId == null) {
            userId = UserHolder.getUser().getId();
        } else {
            // 2.1.校验目标用户存在（他人主页收藏 tab 展示前提）；经 UserMapper 直查 tb_user
            //     （本服务泛型为 Favorite，getById 查的是收藏表，必须用 UserMapper 判定用户存在）
            if (userMapper.selectById(userId) == null) {
                return Result.fail("用户不存在");
            }
            // 2.2.【隐私设置的实现】他人收藏列表可见性校验：本人（显式传自己 id）始终可见；
            //     他人查看时 favorites_visible=0 拦截；UserInfo 不存在=默认公开
            if (!UserHolder.getUser().getId().equals(userId)) {
                UserInfo targetInfo = userInfoMapper.selectById(userId);
                Integer favoritesVisible = targetInfo == null ? null : targetInfo.getFavoritesVisible();
                if (targetInfo != null && favoritesVisible != null && favoritesVisible == 0) {
                    return Result.fail("对方设置了隐私，该内容不可见");
                }
            }
        }
        // 3.按 create_time 倒序分页查该用户该类型收藏
        Page<Favorite> page = query()
                .eq("user_id", userId)
                .eq("target_type", type)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Favorite> records = page.getRecords();
        // 4.逐条 join 目标（游记/景点）取标题与首图；目标已删除则跳过该条
        List<Map<String, Object>> result = new ArrayList<>(records.size());
        for (Favorite record : records) {
            Map<String, Object> item = buildFavoriteItem(type, record);
            if (item != null) {
                result.add(item);
            }
        }
        // 5.返回（total=收藏总数，供前端"查看更多"分页）
        return Result.ok(result, page.getTotal());
    }

    /**
     * 【收藏功能的实现】当前用户是否已收藏该目标（详情页收藏按钮回显）
     * <p>
     * 实现细节：COUNT(tb_favorite WHERE user_id=当前用户 AND target_type=? AND target_id=?)，
     * count>0 即已收藏；直接读 DB，与收藏/取消收藏实时落库保持一致（无缓存一致性问题）。
     *
     * @param targetType 目标类型：note=游记 / attraction=景点
     * @param targetId   目标id
     * @return Result.data=boolean 是否已收藏
     */
    @Override
    public Result status(String targetType, Long targetId) {
        // 1.参数校验
        if (StrUtil.isBlank(targetType) || !TARGET_TYPES.contains(targetType) || targetId == null) {
            return Result.fail("targetType 必须为 note 或 attraction，且 targetId 不能为空");
        }
        Long userId = UserHolder.getUser().getId();
        // 2.查询当前用户是否已收藏
        Long count = query().eq("user_id", userId)
                .eq("target_type", targetType)
                .eq("target_id", targetId)
                .count();
        // 3.返回判断结果
        return Result.ok(count != null && count > 0);
    }

    /**
     * 【收藏功能的实现】某目标的收藏数（详情页收藏数展示，公开）
     * <p>
     * 实现细节：COUNT(tb_favorite WHERE target_type=? AND target_id=?) 实时统计，
     * count 为 null（MySQL COUNT 无匹配返回 0）时兜底 0 防 NPE。
     *
     * @param targetType 目标类型：note=游记 / attraction=景点
     * @param targetId   目标id
     * @return Result.data=收藏数
     */
    @Override
    public Result favoriteCount(String targetType, Long targetId) {
        // 1.参数校验
        if (StrUtil.isBlank(targetType) || !TARGET_TYPES.contains(targetType) || targetId == null) {
            return Result.fail("targetType 必须为 note 或 attraction，且 targetId 不能为空");
        }
        // 2.实时统计目标收藏数
        Long count = query().eq("target_type", targetType).eq("target_id", targetId).count();
        // 3.返回（count==null 兜底 0）
        return Result.ok(count == null ? 0 : count);
    }

    /**
     * 【收藏功能的实现】校验收藏目标是否存在
     * <p>
     * 实现细节：note 走 ITravelNoteService.getById 查 tb_travel_note；attraction 走
     * IAttractionService.getById 查 tb_attraction；查不到（目标已删除/id 非法）返回 false。
     *
     * @param targetType 目标类型（已通过白名单校验）
     * @param targetId   目标id
     * @return true=目标存在
     */
    private boolean targetExists(String targetType, Long targetId) {
        if ("note".equals(targetType)) {
            return travelNoteService.getById(targetId) != null;
        }
        return attractionService.getById(targetId) != null;
    }

    /**
     * 【收藏功能的实现】收藏成功后旁路通知目标作者（type=favorite）
     * <p>
     * 实现细节：
     * - attraction：tb_attraction 无作者字段（只有用户收藏的景点，没有"景点作者"概念），直接跳过不发通知。
     * - note：查 tb_travel_note.user_id 得到作者，文案 {标题前20字}——【通知实时昵称的实现】
     *   content 只存纯动作"收藏了你的游记《{标题前20字}》"（不含昵称前缀），昵称由展示层
     *   queryNotifyList 用实时 fromUser.nickName 拼装 displayText（改昵称自动生效，小红书方案）；
     *   标题>20 字截断加…（与点赞通知同款），targetId=被收藏的游记id。
     * - 静默策略：整体 try-catch，失败仅 log.warn 不影响收藏结果。
     * - 【消息数据补真的实现】自收藏也通知自己：自己收藏自己的游记（note 作者==收藏者）时
     *   同样写入 type=favorite 通知（NotifyServiceImpl.add 不再排除接收者==触发者，
     *   用户要求消息页显示自收藏通知）。
     * - 通知 type=favorite：与 like 一起归入消息页"赞和收藏"分类（见 NotifyController 分类过滤注释）。
     *
     * @param targetType 目标类型（note 才发；attraction 跳过）
     * @param targetId   被收藏目标id（游记id）
     * @param fromUserId 收藏动作触发者用户id
     */
    private void notifyTargetAuthor(String targetType, Long targetId, Long fromUserId) {
        try {
            // 1.景点无作者字段（tb_attraction 无 user_id），跳过收藏通知
            if (!"note".equals(targetType)) {
                return;
            }
            // 2.查被收藏游记的作者
            TravelNote note = travelNoteService.getById(targetId);
            if (note == null || note.getUserId() == null) {
                return;
            }
            // 3.取被收藏游记标题拼纯动作文案（标题>20字截断，与点赞通知风格一致）
            String title = note.getTitle();
            String titlePart = title == null ? "" : (title.length() > 20 ? title.substring(0, 20) + "…" : title);
            // 4.【通知实时昵称的实现】旁路写入 type=favorite 通知：content 只存纯动作文案
            //    "收藏了你的游记《{标题前20字}》"，不含昵称前缀——昵称不落库，由展示层
            //    queryNotifyList 用实时 fromUser.nickName 拼装 displayText（改昵称后历史通知自动生效）
            notifyService.add(note.getUserId(), fromUserId, "favorite", targetId,
                    "收藏了你的游记《" + titlePart + "》");
        } catch (Exception e) {
            // 5.通知失败不影响收藏结果
            log.warn("【收藏功能的实现】收藏通知失败，不影响收藏结果：{}", e.getMessage());
        }
    }

    /**
     * 【收藏功能的实现】组装单条收藏列表项（join 目标标题与首图）
     * <p>
     * 实现细节：
     * - note：取 tb_travel_note.title；attraction：取 tb_attraction.name（无 title 字段）。
     * - image：目标 images 逗号分隔多图，取第 1 张作为列表封面图（见 firstImage）。
     * - 目标已被删除（getById 返回 null）：返回 null，调用方跳过该条收藏。
     *
     * @param type   收藏类型（决定 join 游记还是景点）
     * @param record 收藏关系记录
     * @return Map{id, targetType, targetId, title, image, createTime}；目标已删除返回 null
     */
    private Map<String, Object> buildFavoriteItem(String type, Favorite record) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", record.getId());
        item.put("targetType", record.getTargetType());
        item.put("targetId", record.getTargetId());
        item.put("createTime", record.getCreateTime());
        if ("note".equals(type)) {
            // 1.游记：标题 + 首图
            TravelNote note = travelNoteService.getById(record.getTargetId());
            if (note == null) {
                return null; // 目标游记已删除，跳过该条
            }
            item.put("title", note.getTitle());
            item.put("image", firstImage(note.getImages()));
        } else {
            // 2.景点：名称 + 首图
            Attraction attraction = attractionService.getById(record.getTargetId());
            if (attraction == null) {
                return null; // 目标景点已删除，跳过该条
            }
            item.put("title", attraction.getName());
            item.put("image", firstImage(attraction.getImages()));
        }
        return item;
    }

    /**
     * 【收藏功能的实现】取 images 多图路径的第 1 张作为收藏列表封面图
     * <p>
     * 实现细节：images 为逗号分隔路径（如 /imgs/blogs/4/0.jpg,/imgs/blogs/4/1.jpg），
     * split(",") 取第 1 个；为空返回 null（前端判空兜底）。
     *
     * @param images 逗号分隔的图片路径
     * @return 第 1 张图片路径；为空返回 null
     */
    private String firstImage(String images) {
        if (StrUtil.isBlank(images)) {
            return null;
        }
        String[] parts = images.split(",");
        return parts.length > 0 ? parts[0] : null;
    }

}
