package com.travelplus.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.Result;
import com.travelplus.dto.ScrollResult;
import com.travelplus.dto.UserDTO;
import com.travelplus.entity.TravelNote;
import com.travelplus.entity.Follow;
import com.travelplus.entity.User;
import com.travelplus.mapper.TravelNoteMapper;
import com.travelplus.service.IBrowseRecordService;
import com.travelplus.service.ITravelNoteService;
import com.travelplus.service.IFollowService;
import com.travelplus.service.IUserService;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.travelplus.utils.RedisConstants.NOTE_LIKED_KEY;
import static com.travelplus.utils.RedisConstants.FEED_KEY;

/**
 * 【游记功能的实现】游记服务实现：发布（Feed 推流）、点赞、热门/详情/点赞用户、关注 Feed 滚动分页、删除
 * <p>
 * 实现细节：
 * - 发布链路：save 落库成功后把 noteId 以 score=时间戳 ZADD 进作者所有粉丝的 Feed
 *   （key=feed:{粉丝id}），供关注页滚动拉取（推模式）。
 * - 点赞链路：DB liked 计数与 Redis ZSet note:liked:{noteId}（成员=userId，score=点赞时间）同步维护，
 *   Top5 与点赞态均读 ZSet；DB 更新成功才动 Redis，保持两处一致。
 * - 详情链路：对登录用户写浏览记录（member=note:{id}，ZSet score=时间戳）。
 * - 通用补全：queryTravelNoteUser 补作者昵称/头像，isTravelNoteLiked 补当前用户点赞态（未登录不查）。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
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

    /**
     * 【游记功能的实现】热门游记分页查询（按点赞数倒序）
     * <p>
     * 实现细节：DB 查询按 liked 倒序（点赞多的靠前），每页 SystemConstants.MAX_PAGE_SIZE 条；
     * 逐条 queryTravelNoteUser 补作者昵称/头像、isTravelNoteLiked 补当前登录用户点赞态（未登录不补）。
     * @param current 页码（从1开始）
     * @return 热门游记列表（含 name/icon/isLike）
     */
    @Override
    public Result queryHotTravelNote(Integer current) {
        // 根据用户查询
        Page<TravelNote> page = query()
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
     */
    @Override
    public Result queryTravelNoteById(Long id) {
        // 1.查询note
        TravelNote note = getById(id);
        if (note == null) {
            return Result.fail("游记不存在！");
        }
        // 2.记录浏览（仅登录用户；游客不记录）
        UserDTO user = UserHolder.getUser();
        if (user != null) {
            browseRecordService.record(user.getId(), "note", id);
        }
        // 3.查询note有关的用户
        queryTravelNoteUser(note);
        // 4.查询note是否被点赞
        isTravelNoteLiked(note);
        return Result.ok(note);
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
     * 【游记功能的实现】发布游记并推送给作者粉丝（Feed 推模式）
     * <p>
     * 实现细节：
     * - 归属：note.userId 取自 UserHolder（当前登录用户），不信任前端传参，杜绝代发。
     * - 落库：save(note) 失败 fail "新增游记失败!"，成功后才进入推流。
     * - 推流：查作者全部粉丝（tb_follow WHERE follow_user_id=作者id），逐个把 noteId 以
     *   score=System.currentTimeMillis() ZADD 进粉丝的 Feed（key=feed:{粉丝id}，FEED_KEY+userId），
     *   粉丝在关注页用 queryTravelNoteOfFollow 按时间倒序拉取。
     * @param note 游记（title/images/content/attractionId?，userId 由服务端覆盖）
     * @return Result.data=noteId
     */
    @Override
    public Result saveTravelNote(TravelNote note) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        note.setUserId(user.getId());
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
     * 【游记功能的实现】关注 Feed 滚动分页查询（按发布时间倒序，游标分页）
     * <p>
     * 实现细节：
     * - 数据源：当前用户收件箱 Feed（key=feed:{userId}，saveTravelNote 推流写入的 ZSet，
     *   member=noteId，score=发布毫秒时间戳）。
     * - 滚动：ZREVRANGEBYSCORE key 0 max LIMIT offset 2 取"score≤max"的一页 2 条（时间倒序）；
     *   遍历解析 noteId，并对相同 score 累计 offset（同时间戳多条的翻页偏移量），minTime 取本页最小时间戳。
     * - 补全：IN 查游记 + ORDER BY FIELD(id,...) 保持 Feed 顺序，逐条补作者昵称/头像与当前用户点赞态。
     * - 返回：ScrollResult{list, minTime, offset}，前端下次请求把 minTime/offset 原样传回实现无缝续拉。
     * @param max    上一页返回的 minTime（首次传 Long.MAX_VALUE）
     * @param offset 上一页返回的 offset（首次传 0）
     * @return ScrollResult 滚动分页结果
     */
    @Override
    public Result queryTravelNoteOfFollow(Long max, Integer offset) {
        // 1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询收件箱 ZREVRANGEBYSCORE key Max Min LIMIT offset count
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        // 3.非空判断
        if (typedTuples == null || typedTuples.isEmpty()) {
            return Result.ok();
        }
        // 4.解析数据：noteId、minTime（时间戳）、offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0; // 2
        int os = 1; // 2
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) { // 5 4 4 2 2
            // 4.1.获取id
            ids.add(Long.valueOf(tuple.getValue()));
            // 4.2.获取分数(时间戳）
            long time = tuple.getScore().longValue();
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }

        // 5.根据id查询note
        String idStr = StrUtil.join(",", ids);
        List<TravelNote> notes = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();

        for (TravelNote note : notes) {
            // 5.1.查询note有关的用户
            queryTravelNoteUser(note);
            // 5.2.查询note是否被点赞
            isTravelNoteLiked(note);
        }

        // 6.封装并返回
        ScrollResult r = new ScrollResult();
        r.setList(notes);
        r.setOffset(os);
        r.setMinTime(minTime);

        return Result.ok(r);
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
