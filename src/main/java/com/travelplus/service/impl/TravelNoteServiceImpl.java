package com.travelplus.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.entity.TravelNote;
import com.travelplus.entity.Attraction;
import com.travelplus.entity.Favorite;
import com.travelplus.entity.Follow;
import com.travelplus.entity.User;
import com.travelplus.mapper.TravelNoteMapper;
import com.travelplus.mapper.AttractionMapper;
import com.travelplus.mapper.FavoriteMapper;
import com.travelplus.service.IBrowseRecordService;
import com.travelplus.service.ITravelNoteService;
import com.travelplus.service.IFollowService;
import com.travelplus.service.INotifyService;
import com.travelplus.service.IUserService;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.travelplus.utils.RedisConstants.NOTE_LIKED_KEY;
import static com.travelplus.utils.RedisConstants.FEED_KEY;

/**
 * 【游记功能的实现】游记服务实现：发布（Feed 推流）、点赞、热门/详情/点赞用户、关注 Feed 实时分页、编辑、删除
 * <p>
 * 实现细节：
 * - 发布链路：save 落库成功后把 noteId 以 score=时间戳 ZADD 进作者所有粉丝的 Feed
 *   （key=feed:{粉丝id}）——【关注Feed的实现】关注页读取已改为实时查询（queryTravelNoteOfFollow
 *   按关注列表+可见性实时 join，不消费 feed 收件箱），推流写入保留为历史实现。
 * - 可见性（【笔记可见性的实现】）：visible=1 仅自己可见——热门/他人主页/关注 Feed/搜索统一按
 *   (visible = 0 OR user_id = 当前用户) 过滤，详情对非作者 fail"该笔记仅作者可见"。
 * - 编辑（【笔记编辑的实现】）：updateNote 仅作者本人可编辑，非空字段更新。
 * - 点赞链路：DB liked 计数与 Redis ZSet note:liked:{noteId}（成员=userId，score=点赞时间）同步维护，
 *   Top5 与点赞态均读 ZSet；DB 更新成功才动 Redis，保持两处一致。
 * - 详情链路：对登录用户写浏览记录（member=note:{id}，ZSet score=时间戳）。
 * - 通用补全：queryTravelNoteUser 补作者昵称/头像，isTravelNoteLiked 补当前用户点赞态（未登录不查）。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Slf4j
@Service
public class TravelNoteServiceImpl extends ServiceImpl<TravelNoteMapper, TravelNote> implements ITravelNoteService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    @Resource
    private IBrowseRecordService browseRecordService;

    @Resource
    private INotifyService notifyService;

    @Resource
    private FavoriteMapper favoriteMapper;

    /** 【游记关联景点的实现】关联景点信息用（join tb_attraction 取名称/首图） */
    @Resource
    private AttractionMapper attractionMapper;

    /**
     * 【游记功能的实现】热门游记分页查询（按点赞数倒序）
     * <p>
     * 实现细节：DB 查询按 liked 倒序（点赞多的靠前），每页 SystemConstants.MAX_PAGE_SIZE 条；
     * 逐条 queryTravelNoteUser 补作者昵称/头像、isTravelNoteLiked 补当前登录用户点赞态（未登录不补）。
     * 【笔记可见性的实现】热门列表只展示公开笔记（visible=0）或当前登录用户自己的笔记
     * （含仅自己可见 visible=1 的本人笔记）；过滤条件 (visible = 0 OR user_id = 当前用户)，
     * 未登录游客时仅条件前半段（currentUserId=null 不追加 OR），他人隐藏笔记不出现。
     * @param current 页码（从1开始）
     * @return 热门游记列表（含 name/icon/isLike）
     */
    @Override
    public Result queryHotTravelNote(Integer current) {
        // 【笔记可见性的实现】过滤条件：可见公开 或 本人（未登录时仅公开）
        UserDTO currentUser = UserHolder.getUser();
        Long currentUserId = currentUser == null ? null : currentUser.getId();
        Page<TravelNote> page = query()
                .and(w -> {
                    w.eq("visible", 0);
                    if (currentUserId != null) {
                        w.or().eq("user_id", currentUserId);
                    }
                })
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<TravelNote> records = page.getRecords();
        // 查询用户
        records.forEach(note -> {
            this.queryTravelNoteUser(note);
            this.isTravelNoteLiked(note);
        });
        return Result.ok(records);
    }

    /**
     * 【浏览记录的实现】游记详情查询：登录用户浏览时写入浏览记录（Redis ZSet）
     * <p>
     * 实现细节：
     * - 记录时机：查询详情成功（游记存在）后才记录，返回"游记不存在"时不记录；
     *   只对已登录用户记录（UserHolder 判空，游客浏览不产生记录，避免未登录写 Redis 脏数据）。
     * - 记录内容：member=note:{id}，score=当前毫秒时间戳，见 BrowseRecordServiceImpl.record 注释。
     * - 【笔记可见性的实现】仅自己可见的笔记（visible=1）非作者访问直接 fail
     *   "该笔记仅作者可见"（含未登录游客=非作者），作者本人正常返回并记录浏览。
     */
    @Override
    public Result queryTravelNoteById(Long id) {
        // 1.查询note
        TravelNote note = getById(id);
        if (note == null) {
            return Result.fail("游记不存在！");
        }
        // 1.1.【笔记可见性的实现】详情可见性校验：visible=1 且当前访问者非作者 → 拦截
        UserDTO currentUser = UserHolder.getUser();
        if (note.getVisible() != null && note.getVisible() == 1
                && (currentUser == null || !currentUser.getId().equals(note.getUserId()))) {
            return Result.fail("该笔记仅作者可见");
        }
        // 2.记录浏览（仅登录用户；游客不记录）
        if (currentUser != null) {
            browseRecordService.record(currentUser.getId(), "note", id);
        }
        // 3.查询note有关的用户
        queryTravelNoteUser(note);
        // 4.查询note是否被点赞
        isTravelNoteLiked(note);
        // 5.补充收藏状态与收藏数（【收藏功能的实现】）
        attachFavoriteInfo(note);
        // 6.【游记关联景点的实现】若 note.attractionId 非空，join 景点信息返回（游记正文的景点入口）
        attachAttractionInfo(note);
        return Result.ok(note);
    }

    /**
     * 【游记关联景点的实现】补充游记关联的景点信息（详情返回）
     * <p>
     * 实现细节：
     * - 仅当 note.attractionId 非空才 join（关联可选，未关联景点保持 null，前端不渲染入口）。
     * - attractionMapper.selectById 查 tb_attraction，返回 Map{id, name, image}——
     *   image 取景点 images 逗号分隔首图；景点已被删除（查不到）则置 null 并跳过。
     * - 与详情其它补充（作者/点赞态/收藏态）并行，互不影响。
     * @param note 游记（写 attraction 非表字段）
     */
    private void attachAttractionInfo(TravelNote note) {
        if (note.getAttractionId() == null) {
            return;
        }
        Attraction attraction = attractionMapper.selectById(note.getAttractionId());
        if (attraction == null) {
            return;
        }
        Map<String, Object> attractionInfo = new HashMap<>(3);
        attractionInfo.put("id", attraction.getId());
        attractionInfo.put("name", attraction.getName());
        if (StrUtil.isNotBlank(attraction.getImages())) {
            attractionInfo.put("image", attraction.getImages().split(",")[0]);
        }
        note.setAttraction(attractionInfo);
    }

    /**
     * 【收藏功能的实现】补充游记收藏状态与收藏数（详情返回）
     * <p>
     * 实现细节：
     * - favoriteCount：实时 COUNT(tb_favorite WHERE target_type='note' AND target_id=?)，
     *   收藏/取消收藏均实时落库且无缓存，直接统计即最新值。
     * - isFavorite：与 isLike 同模式——仅登录用户判断（UserHolder 判空），未登录保持 null；
     *   COUNT(tb_favorite WHERE user_id=? AND target_type='note' AND target_id=?) > 0 即已收藏。
     * - 通过 FavoriteMapper 直查避免注入 IFavoriteService 造成与 FavoriteServiceImpl 循环依赖。
     * @param note 游记实体（写 isFavorite/favoriteCount 非表字段）
     */
    private void attachFavoriteInfo(TravelNote note) {
        // 1.收藏数实时统计（不依赖缓存）
        Long favoriteCount = favoriteMapper.selectCount(
                new QueryWrapper<Favorite>()
                        .eq("target_type", "note")
                        .eq("target_id", note.getId()));
        note.setFavoriteCount(favoriteCount == null ? 0 : favoriteCount.intValue());
        // 2.是否收藏：仅登录用户判断（游客不查，isFavorite 保持 null）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return;
        }
        Long favoriteCountByUser = favoriteMapper.selectCount(
                new QueryWrapper<Favorite>()
                        .eq("user_id", user.getId())
                        .eq("target_type", "note")
                        .eq("target_id", note.getId()));
        note.setIsFavorite(favoriteCountByUser != null && favoriteCountByUser > 0);
    }

    private void isTravelNoteLiked(TravelNote note) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            // 用户未登录，无需查询是否点赞
            return;
        }
        Long userId = user.getId();
        // 2.判断当前登录用户是否已经点赞
        String key = "note:liked:" + note.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        note.setIsLike(score != null);
    }

    /**
     * 【游记点赞的实现】点赞/取消点赞切换（DB 计数与 Redis 点赞集合同步）
     * <p>
     * 实现细节：
     * - 状态判定：以 Redis ZSet note:liked:{id} 中是否包含当前用户为准（score 存在即已赞），
     *   与 isTravelNoteLiked 的判定口径一致，避免 DB/Redis 状态分裂。
     * - 点赞：DB setSql("liked = liked + 1") 自增成功后 ZADD userId（score=System.currentTimeMillis()，
     *   作为 queryTravelNoteLikes Top5 的时间排序依据）；取消：DB 自减成功后 ZREM userId。
     * - 一致性：DB 更新成功才动 Redis（DB 失败时集合不变，下次查询仍为未赞状态，可重试）。
     * @param id 游记id
     * @return 切换成功返回 ok
     */
    @Override
    public Result likeTravelNote(Long id) {
        // 1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2.判断当前登录用户是否已经点赞
        String key = NOTE_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 3.如果未点赞，可以点赞
            // 3.1.数据库点赞数 + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            // 3.2.保存用户到Redis的set集合  zadd key value score
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
                // 3.3.【通知系统的实现】点赞动作旁路通知游记作者（仅"未赞→点赞"发，取消赞不发；失败静默）
                notifyNoteAuthorLiked(id, userId);
            }
        } else {
            // 4.如果已点赞，取消点赞
            // 4.1.数据库点赞数 -1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            // 4.2.把用户从Redis的set集合移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 【通知系统的实现】点赞成功旁路通知游记作者（type=like）
     * <p>
     * 实现细节：
     * - 触发时机：仅"未赞→点赞"动作（score==null 分支）调用，取消赞不发通知。
     * - 文案：【通知实时昵称的实现】content 只存纯动作"赞了你的游记《{标题前20字}》"
     *   （不含昵称前缀），昵称由展示层 queryNotifyList 用实时 fromUser.nickName 拼装 displayText
     *   （改昵称后历史通知自动显示新昵称，小红书方案）；标题超 20 字截断加省略号，避免文案过长。
     * - 静默策略：查游记/拼文案/写通知整体 try-catch，失败仅 log.warn 不影响点赞结果。
     * - 【消息数据补真的实现】自赞也通知自己：自己给自己点赞（触发者==作者）时同样写入
     *   type=like 通知（NotifyServiceImpl.add 不再排除接收者==触发者，用户要求消息页显示自赞）；
     *   文案 赞了你的游记《...》 即自己赞自己（昵称前缀由展示层实时拼 displayText），前端据此展示。
     *
     * @param noteId    被赞游记id
     * @param likeUserId 点赞用户id
     */
    private void notifyNoteAuthorLiked(Long noteId, Long likeUserId) {
        try {
            TravelNote note = getById(noteId);
            if (note == null || note.getUserId() == null) {
                return;
            }
            // 【通知实时昵称的实现】content 只存纯动作文案"赞了你的游记《{标题前20字}》"，
            // 不含昵称前缀（标题>20字截断，与收藏通知一致）——昵称由展示层实时拼装 displayText，
            // 用户改昵称后历史通知自动显示新昵称（小红书方案：昵称不落库、展示实时拼装）。
            String title = note.getTitle();
            String titlePart = title == null ? "" : (title.length() > 20 ? title.substring(0, 20) + "…" : title);
            notifyService.add(note.getUserId(), likeUserId, "like", noteId,
                    "赞了你的游记《" + titlePart + "》");
        } catch (Exception e) {
            log.warn("【通知系统的实现】点赞通知失败，不影响点赞结果：{}", e.getMessage());
        }
    }

    /**
     * 【游记点赞的实现】查询游记点赞数 Top5 用户
     * <p>
     * 实现细节：ZRANGE note:liked:{id} 0 4 取点赞时间最早（score 最小）的 5 个 userId；
     * 空集合直接返回空列表；IN 查 tb_user 并用 ORDER BY FIELD(id,idStr) 保持 ZSet 的顺序
     * （与点赞先后一致），转 UserDTO 返回。
     * @param id 游记id
     * @return Top5 点赞用户列表（无则空列表）
     */
    @Override
    public Result queryTravelNoteLikes(Long id) {
        String key = NOTE_LIKED_KEY + id;
        // 1.查询top5的点赞用户 zrange key 0 4
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (top5 == null || top5.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析出其中的用户id
        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", ids);
        // 3.根据用户id查询用户 WHERE id IN ( 5 , 1 ) ORDER BY FIELD(id, 5, 1)
        List<UserDTO> userDTOS = userService.query()
                .in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4.返回
        return Result.ok(userDTOS);
    }

    /**
     * 【游记功能的实现】发布游记并推送给作者粉丝（Feed 推模式，历史实现）
     * <p>
     * 实现细节：
     * - 归属：note.userId 取自 UserHolder（当前登录用户），不信任前端传参，杜绝代发。
     * - 可见性（【笔记可见性的实现】）：note.visible 支持请求体传入（0=公开默认/1=仅自己可见），
     *   null 归一为 0（与建表 DEFAULT 一致）；仅 0/1 合法，越界 fail 不入库。
     * - 落库：save(note) 失败 fail "新增游记失败!"，成功后才进入推流。
     * - 推流：查作者全部粉丝（tb_follow WHERE follow_user_id=作者id），逐个把 noteId 以
     *   score=System.currentTimeMillis() ZADD 进粉丝的 Feed（key=feed:{粉丝id}，FEED_KEY+userId）。
     *   【关注Feed的实现】注：关注页读取已改为实时查询（queryTravelNoteOfFollow 按关注列表+可见性
     *   join），不再消费 feed 收件箱；此处推流保留为历史实现（不删，避免破坏既有 Redis 数据语义）。
     * @param note 游记（title/images/content/attractionId?/visible?，userId 由服务端覆盖）
     * @return Result.data=noteId
     */
    @Override
    public Result saveTravelNote(TravelNote note) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        note.setUserId(user.getId());
        // 1.1.【笔记可见性的实现】可见性归一与校验：null 默认 0=公开；仅 0/1 合法
        if (note.getVisible() == null) {
            note.setVisible(0);
        } else if (note.getVisible() != 0 && note.getVisible() != 1) {
            return Result.fail("可见性取值只能为0或1");
        }
        // 1.2.【游记关联景点的实现】关联景点校验（可选）：attractionId 为 null 不关联；
        //     非 null 时校验景点存在，不存在 fail"关联的景点不存在"（防游记挂在已下架景点下）
        if (note.getAttractionId() != null && attractionMapper.selectById(note.getAttractionId()) == null) {
            return Result.fail("关联的景点不存在");
        }
        // 2.保存游记游记
        boolean isSuccess = save(note);
        if(!isSuccess){
            return Result.fail("新增游记失败!");
        }
        // 3.查询游记作者的所有粉丝 select * from tb_follow where follow_user_id = ?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4.推送游记id给所有粉丝
        for (Follow follow : follows) {
            // 4.1.获取粉丝id
            Long userId = follow.getUserId();
            // 4.2.推送
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, note.getId().toString(), System.currentTimeMillis());
        }
        // 5.返回id
        return Result.ok(note.getId());
    }

    /**
     * 【笔记编辑的实现】编辑已发布笔记（仅作者本人可编辑，非空字段更新）
     * <p>
     * 实现细节：
     * - 需登录：PUT /note/{id} 在 LoginInterceptor 拦截范围内（/note/** 除 /note/hot 外均需登录）。
     * - 作者校验：笔记归属以服务端 UserHolder 为准（不信任前端传参），exist.getUserId() != 当前登录用户
     *   时 fail "无权编辑该游记"，防止越权编辑他人内容；笔记不存在（已删除/非法 id）fail "游记不存在"。
     * - 非空更新：title/content/images/attractionId/visible 任一提交才更新（updateById 默认策略
     *   仅更新非 null 字段，未提交字段保持原值）——字段 null 表示"不修改"，避免整表覆盖。
     * - 字段校验：visible 仅 0/1（越界 fail 不入库）；attractionId 非空时校验景点存在
     *   （与 saveTravelNote 的关联景点校验同规则）。
     * - 其余字段（liked/comments 等计数）不可编辑，杜绝通过编辑接口刷数据。
     * - update_time 由 DB ON UPDATE CURRENT_TIMESTAMP 自动刷新，无需显式赋值。
     *
     * @param id   待编辑的游记id
     * @param note 编辑入参（title/content/images/attractionId/visible 均可选，非空才更新）
     * @return 编辑成功返回 ok
     */
    @Override
    public Result updateNote(Long id, TravelNote note) {
        // 1.获取当前登录用户（PUT /note/{id} 需登录）
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        // 2.查询原笔记，不存在 fail（编辑已删除/不存在的笔记走此分支，天然幂等）
        TravelNote exist = getById(id);
        if (exist == null) {
            return Result.fail("游记不存在");
        }
        // 3.作者校验：仅作者本人可编辑（防越权修改他人笔记）
        if (!currentUser.getId().equals(exist.getUserId())) {
            return Result.fail("无权编辑该游记");
        }
        // 4.字段校验：visible 仅 0/1；attractionId 非空时校验景点存在
        if (note.getVisible() != null && note.getVisible() != 0 && note.getVisible() != 1) {
            return Result.fail("可见性取值只能为0或1");
        }
        if (note.getAttractionId() != null && attractionMapper.selectById(note.getAttractionId()) == null) {
            return Result.fail("关联的景点不存在");
        }
        // 5.非空字段更新（updateById 默认仅更新非 null 字段，null 表示不修改保持原值）
        TravelNote update = new TravelNote();
        update.setId(id);
        if (StrUtil.isNotBlank(note.getTitle())) {
            update.setTitle(note.getTitle());
        }
        if (StrUtil.isNotBlank(note.getContent())) {
            update.setContent(note.getContent());
        }
        if (StrUtil.isNotBlank(note.getImages())) {
            update.setImages(note.getImages());
        }
        if (note.getAttractionId() != null) {
            update.setAttractionId(note.getAttractionId());
        }
        if (note.getVisible() != null) {
            update.setVisible(note.getVisible());
        }
        updateById(update);
        return Result.ok();
    }

    /**
     * 【关注Feed的实现】关注流实时查询分页（我关注的所有人发布的可见游记，按发布时间倒序）
     * <p>
     * 实现细节：
     * - 为什么改实时查询：原实现是 Redis ZSet 推送制（feed:{userId} 收件箱）——只在"发布游记时"
     *   推送给当时的粉丝 + 关注时补齐历史，二者都依赖发布时点：若发布时还不是粉丝（或补齐逻辑
     *   漏推），关注频道就会缺失 TA 的游记/不全。实时查询不再依赖任何推送时点，每次请求按
     *   "我关注了谁（tb_follow user_id=me）→ 他们发布的游记"实时 join，保证数据与关注人数一致、
     *   完整不缺失（新增关注/取关/新发布即时反映，无缓存一致性问题）。
     * - 查询：① 一次查 tb_follow WHERE user_id=me 取关注对象集合（follow_user_id，避免 N+1）；
     *   ② 无关注直接返回空列表；③ IN(user_id, 关注集合) 查游记并过滤可见性——他人隐藏笔记
     *   （visible=1）不出现，仅保留公开笔记（visible=0）或作者本人的笔记（作者本人始终可见），
     *   条件 (visible = 0 OR user_id = 当前用户)。
     * - 分页：create_time 倒序 + id 倒序二级排序（同时间发布的翻页稳定），Page(current, MAX_PAGE_SIZE=10)。
     * - 补全：复用 queryTravelNoteUser（作者 name/icon）与 isTravelNoteLiked（当前用户点赞态）。
     * - 返回：data=当前页 List&lt;TravelNote&gt;，total=符合条件总数（Result.ok(list, total)）。
     *
     * @param current 页码（从1开始）
     * @return 当前页关注游记列表（含 name/icon/isLike），total=符合条件的总数
     */
    @Override
    public Result queryTravelNoteOfFollow(Integer current) {
        // 1.获取当前用户（/note/of/follow 需登录，UserHolder 非空）
        Long userId = UserHolder.getUser().getId();
        // 2.实时查关注列表：tb_follow WHERE user_id=me 的全部关注对象 id（一次查询，无 N+1）
        List<Follow> follows = followService.query().eq("user_id", userId).list();
        List<Long> followUserIds = follows.stream().map(Follow::getFollowUserId).collect(Collectors.toList());
        // 3.未关注任何人 → 关注流为空
        if (followUserIds.isEmpty()) {
            return Result.ok(Collections.emptyList(), 0L);
        }
        // 4.实时 join 游记：IN(关注集合) + 可见性过滤（公开 或 本人），按发布时间倒序分页
        //   二级排序 orderByDesc("id")：同 create_time 的游记按 id 降序，保证翻页结果稳定不重不漏
        Page<TravelNote> page = query()
                .in("user_id", followUserIds)
                .and(w -> {
                    w.eq("visible", 0);
                    w.or().eq("user_id", userId);
                })
                .orderByDesc("create_time")
                .orderByDesc("id")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<TravelNote> records = page.getRecords();
        // 5.逐条补作者昵称/头像与当前用户点赞态（复用既有补全逻辑）
        for (TravelNote note : records) {
            queryTravelNoteUser(note);
            isTravelNoteLiked(note);
        }
        // 6.返回当前页列表与总数
        return Result.ok(records, page.getTotal());
    }

    private void queryTravelNoteUser(TravelNote note) {
        Long userId = note.getUserId();
        User user = userService.getById(userId);
        note.setName(user.getNickName());
        note.setIcon(user.getIcon());
    }

    /**
     * 【游记删除的实现】删除指定游记并清理关联的 Redis 缓存
     * <p>
     * 实现细节：
     * - 作者校验：笔记归属以服务端 UserHolder 为准（不信任前端传参），
     *   note.getUserId() != 当前登录用户时 fail "无权删除该游记"，防止越权删除他人内容；
     *   对同一游记重复删除时第二次查无记录走 "游记不存在" 分支，天然幂等。
     * - DB：removeById 物理删除 tb_travel_note 记录（与点赞数/评论数计数一并消失）。
     * - Redis 清理：
     *   1. 删除点赞集合 note:liked:{id}（NOTE_LIKED_KEY），避免点赞用户集合成为孤儿 key 残留；
     *   2. 从作者自己的关注 Feed（FEED_KEY + 作者id 的 ZSet）移除该 noteId。
     *   说明：saveTravelNote 推流是把 noteId 写入作者所有粉丝的 feed，作者自己的 feed 并不包含本笔记；
     *   此处仅清理作者自身 feed 是演示环境的简化实现——粉丝 feed 中的失效 noteId 在滚动拉取时
     *   会因查无此笔记被过滤，若要彻底清理需遍历作者全部粉丝逐个 remove（复杂度与粉丝数成正比），演示不做。
     * - DELETE /note/{id} 在 LoginInterceptor 拦截范围内（/note/** 除 /note/hot 外均需登录），
     *   与既有 /note 写接口的鉴权策略一致。
     */
    @Override
    public Result deleteNote(Long id) {
        // 1.查询游记，不存在直接 fail（重复删除走此分支，幂等）
        TravelNote note = getById(id);
        if (note == null) {
            return Result.fail("游记不存在");
        }
        // 2.作者校验：仅作者本人可删，防越权
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null || !currentUser.getId().equals(note.getUserId())) {
            return Result.fail("无权删除该游记");
        }
        // 3.删除数据库记录
        removeById(id);
        // 4.清理相关 Redis
        // 4.1.删除该游记的点赞集合 note:liked:{id}
        stringRedisTemplate.delete(NOTE_LIKED_KEY + id);
        // 4.2.从作者自己的关注 Feed 中移除该 noteId（演示简化，详见注释）
        Long authorId = note.getUserId();
        stringRedisTemplate.opsForZSet().remove(FEED_KEY + authorId, id.toString());
        return Result.ok();
    }
}
