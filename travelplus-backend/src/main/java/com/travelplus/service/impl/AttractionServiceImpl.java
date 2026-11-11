package com.travelplus.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.entity.Attraction;
import com.travelplus.entity.AttractionCheckin;
import com.travelplus.entity.AttractionComment;
import com.travelplus.entity.AttractionRating;
import com.travelplus.entity.Favorite;
import com.travelplus.entity.Ticket;
import com.travelplus.mapper.AttractionCheckinMapper;
import com.travelplus.mapper.AttractionCommentMapper;
import com.travelplus.mapper.AttractionMapper;
import com.travelplus.mapper.AttractionRatingMapper;
import com.travelplus.mapper.FavoriteMapper;
import com.travelplus.mapper.SeckillTicketMapper;
import com.travelplus.mapper.TicketMapper;
import com.travelplus.service.IAttractionService;
import com.travelplus.service.IBrowseRecordService;
import com.travelplus.service.cache.AttractionBloomFilterService;
import com.travelplus.utils.CacheClient;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.travelplus.utils.RedisConstants.*;

/**
 * 【景点服务的实现】景点服务实现：详情查询（缓存防穿透/防击穿 + 浏览记录 + 点赞/收藏态 + 动态评分 +
 * 打卡态/来过统计 + 实时评论数）、点赞/取消点赞（Redis ZSet，无 DB 计数）、打分（1~5 upsert）、
 * 打卡（唯一键幂等）、城市推荐（destination 分组/筛选）、更新（Cache Aside 删缓存）、按类型查询（Geo 距离排序）
 * <p>
 * 实现细节：
 * - queryById 走三道防穿透防线：① 布隆过滤器前置拦截（【布隆过滤器防穿透的实现】）→
 *   ② cacheClient.queryWithPassThrough 缓存空值防穿透（互斥锁与逻辑过期两套防击穿方案注释保留备选）；
 *   查询成功后对登录用户写浏览记录（member=attraction:{id}），
 *   并补充当前登录用户点赞态 isLike（attachLikeInfo，Redis attraction:liked:{id}）与收藏态 isFavorite/favoriteCount，
 *   以及动态评分/打卡/评论数/最低门票价等运行时统计（attachRatingInfo/attachCheckinInfo/attachCommentCount/attachTicketPrice）。
 * - likeAttraction 仿游记点赞 likeTravelNote：Redis ZSet attraction:liked:{id} 切换（再次点击=取消），
 *   与游记唯一差异——景点表无 liked 计数列，不做 DB 计数同步（赞过仅列表用途，全走 Redis）。
 * - rate：唯一约束 upsert（uk_ar(user_id,attraction_id)，先查后插/改，用户可改分），动态均分由详情查询 AVG。
 * - checkin：唯一约束幂等（uk_ac(user_id,attraction_id)，已打卡返回 ok 不重复计数），打卡记录即足迹来源。
 * - update 先更新 DB 再删除 Redis 缓存（Cache Aside），保证下次查询拉到新数据。
 * - queryAttractionByType：x/y 为空走 DB 分页，非空走 Redis GEO（GEOSEARCH + BYRADIUS 5000m +
 *   WITHDISTANCE）按距离排序分页；入口 5 秒限 5 次由 Controller @RateLimiter 实现。
 * - queryDestinations/queryByDestination：按 tb_attraction.destination 列分组/筛选（城市推荐）。
 * - **列表动态评分与打卡统计（attachListInfo）**：所有返回 List&lt;Attraction&gt; 的列表接口
 *   （queryAttractionByType DB/GEO 两分支、queryByDestination、queryAttractionByName）统一在返回前
 *   批量补充 avgScore/ratingCount/checkinCount/avgPrice/hasTicket/hasSeckill——评分聚合与打卡统计用
 *   GROUP BY + IN 一次完成（防 N+1），avgScore 与详情同源（AVG×10，无评分回退原 score），
 *   修复"主页卡片原 score vs 详情 avgScore 评分不一致"bug；avgPrice 实时取 tb_ticket 最低
 *   pay_value（分）与详情门票列表同源（修复"列表¥X起 vs 详情门票价不一致"bug）；
 *   门票信息批量 MIN/GROUP BY/JOIN 判定免费/收费/秒杀。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Service
public class AttractionServiceImpl extends ServiceImpl<AttractionMapper, Attraction> implements IAttractionService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private IBrowseRecordService browseRecordService;

    @Resource
    private FavoriteMapper favoriteMapper;

    /** 【动态评分的实现】评分表 Mapper（均分 AVG 聚合 + 打分 upsert + 我的评分） */
    @Resource
    private AttractionRatingMapper ratingMapper;

    /** 【打卡与足迹的实现】打卡表 Mapper（打卡幂等判定 + 来过统计 COUNT） */
    @Resource
    private AttractionCheckinMapper checkinMapper;

    /** 【景点评论的实现】评论表 Mapper（详情评论数实时 COUNT，不维护 tb_attraction.comments） */
    @Resource
    private AttractionCommentMapper commentMapper;

    /** 【门票信息的实现】门票表 Mapper（列表 hasTicket 批量 DISTINCT 判定，免费/收费） */
    @Resource
    private TicketMapper ticketMapper;

    /** 【门票信息的实现】秒杀券表 Mapper（列表 hasSeckill 批量 JOIN 判定，秒杀活动） */
    @Resource
    private SeckillTicketMapper seckillTicketMapper;

    /** 【布隆过滤器防穿透的实现】景点布隆过滤器服务（详情查询第一道防线：确定不存在的 id 直接拦截，不查缓存/DB）。
     * 依赖仅 redissonClient + attractionMapper，与当前 Service 无环，可安全注入。 */
    @Resource
    private AttractionBloomFilterService bloomFilterService;

    /**
     * 【景点详情缓存查询的实现】景点详情查询：缓存防穿透 + 登录用户浏览记录
     * <p>
     * 实现细节：
     * - 三道防穿透防线：① 布隆过滤器（bloomFilterService.contains 前置拦截，确定不存在的 id
     *   直接 fail，不查缓存/DB，见【布隆过滤器防穿透的实现】）；② Redis 缓存查询
     *   cacheClient.queryWithPassThrough(CACHE_ATTRACTION_KEY, id, ...) 命中直接返回；
     *   ③ 缓存未命中回源 DB，DB 无数据写 2min 空值占位（TTL 较短）防缓存穿透，高并发下不直接打 DB；
     *   备选方案注释保留——queryWithMutex（互斥锁）与 queryWithLogicalExpire（逻辑过期）均为防缓存击穿，
     *   可按需切换，当前默认穿透方案。
     * - 结果兜底：布隆误判（1%）放行的不存在 id 最终由缓存/DB 均无数据返回 null → fail "景点不存在！"，
     *   与第一道拦截两层配合。
     * - 浏览记录：查询成功（景点存在）后，仅对已登录用户（UserHolder 判空）调用
     *   browseRecordService.record(userId, "attraction", id)（ZSet member=attraction:{id}，
     *   score=毫秒时间戳），游客浏览不产生记录。
     * @param id 景点id
     * @return 景点详情；不存在 fail "景点不存在！"
     */
    @Override
    public Result queryById(Long id) {
        // 【布隆过滤器防穿透的实现】第一道防线：布隆过滤器前置拦截——
        // contains=false 表示布隆确定该 id 不存在（DB 中无此景点），直接 fail 返回，
        // 不查 Redis 缓存、不查 DB，也不写空值占位 key；恶意批量不存在的 id 在此被 O(1) 挡下，
        // 避免其穿透到空值占位路径写出大量垃圾 key 撑爆 Redis 内存。
        if (!bloomFilterService.contains(id)) {
            return Result.fail("景点不存在！");
        }
        // 【布隆过滤器防穿透的实现】第二道防线：缓存查询——contains=true（可能存在，含 1% 误判）
        // 放行至 cacheClient.queryWithPassThrough：命中 Redis 缓存直接返回。
        // 第三道防线：DB + 空值占位——缓存未命中回源 DB，DB 无数据写 2min 空值占位（本实现保持不动）。
        // 两层兜底配合：布隆误判的不存在 id 会走到缓存/DB，最终由下方 attraction == null 兜底 fail。
        // 解决缓存穿透
        Attraction attraction = cacheClient
                .queryWithPassThrough(CACHE_ATTRACTION_KEY, id, Attraction.class, this::getById, CACHE_ATTRACTION_TTL, TimeUnit.MINUTES);

        // 互斥锁解决缓存击穿
        // Attraction attraction = cacheClient
        //         .queryWithMutex(CACHE_ATTRACTION_KEY, id, Attraction.class, this::getById, CACHE_ATTRACTION_TTL, TimeUnit.MINUTES);

        // 逻辑过期解决缓存击穿
        // Attraction attraction = cacheClient
        //         .queryWithLogicalExpire(CACHE_ATTRACTION_KEY, id, Attraction.class, this::getById, 20L, TimeUnit.SECONDS);

        if (attraction == null) {
            return Result.fail("景点不存在！");
        }
        // 7.记录浏览（仅登录用户；游客不记录）
        UserDTO user = UserHolder.getUser();
        if (user != null) {
            browseRecordService.record(user.getId(), "attraction", id);
        }
        // 8.补充收藏状态与收藏数（【收藏功能的实现】）
        attachFavoriteInfo(attraction);
        // 8.1.【景点点赞的实现】补充当前登录用户点赞态 isLike（仅登录用户判断，未登录保持 null）
        attachLikeInfo(attraction);
        // 8.2.【动态评分的实现】补充动态均分/评分人数/当前用户打分（avgScore/ratingCount/myRating）
        attachRatingInfo(attraction);
        // 8.3.【打卡与足迹的实现】补充当前用户打卡态与打卡总人数（checkedIn/checkinCount）
        attachCheckinInfo(attraction);
        // 8.4.【景点评论的实现】评论数由评论表 COUNT 实时统计（覆盖种子静态 comments 字段，保持评论数与表一致）
        attachCommentCount(attraction);
        // 8.5.【门票信息的实现】补充最低门票价 avgPrice（覆盖 DB 静态 avg_price 列，
        //     与列表"¥X 起"同源：均取 tb_ticket 最低 pay_value，列表/详情价格一致）
        attachTicketPrice(attraction);
        // 9.返回
        return Result.ok(attraction);
    }

    /**
     * 【景点点赞的实现】景点点赞/取消点赞切换（仿游记点赞，无 DB 计数）
     * <p>
     * 实现细节：
     * - 需登录：本方法直接从 UserHolder 取当前登录用户（PUT /attraction/like/{id} 已被
     *   LoginInterceptor 拦截，见 MvcConfig exclude 调整，未登录返回 401）。
     * - 切换语义与游记点赞完全一致：查 Redis ZSet attraction:liked:{id}（ATTRACTION_LIKED_KEY）
     *   中当前用户 score——不存在=未点赞，ZADD 用户（member=userId，score=System.currentTimeMillis()
     *   点赞时间戳，供"赞过列表"按时间倒序）；存在=已点赞（再次点击=取消），ZREM 移除。
     * - 与游记点赞（likeTravelNote）唯一差异：景点不做 DB liked ±1——tb_attraction 无 liked
     *   点赞计数字段（表结构无该列），也不新增列，赞过判定/赞过列表全走 Redis ZSet
     *   （内存占用对 demo 规模可忽略，且避免动表结构）。
     * - 返回 ok；是否已点赞由详情/列表接口的 isLike 字段回显（见 attachLikeInfo）。
     * @param id 景点id
     * @return 切换成功返回 ok
     */
    @Override
    public Result likeAttraction(Long id) {
        // 1.获取登录用户（拦截器保证非空；显式判空兜底防未来改动放行）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        // 2.判断当前登录用户是否已经点赞（ZSet 中 score 非空即已点赞）
        String key = ATTRACTION_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null) {
            // 3.未点赞：加入赞过集合，score=点赞时间戳（供赞过列表按时间倒序）
            stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis());
        } else {
            // 4.已点赞：取消点赞，从赞过集合移除
            stringRedisTemplate.opsForZSet().remove(key, userId.toString());
        }
        return Result.ok();
    }

    /**
     * 【景点点赞的实现】补充景点当前登录用户点赞态 isLike（详情返回）
     * <p>
     * 实现细节：
     * - 与游记 isTravelNoteLiked / 收藏 isFavorite 同模式：仅登录用户判断（UserHolder 判空），
     *   未登录保持 null（前端不渲染点赞高亮），与收藏态策略一致。
     * - 判定：Redis ZSet attraction:liked:{id} 中是否含当前用户（opsForZSet().score != null），
     *   赞过集合全走 Redis（景点无 DB liked 列），实时准确。
     * @param attraction 景点实体（写 isLike 非表字段）
     */
    private void attachLikeInfo(Attraction attraction) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            // 游客浏览详情：isLike 保持 null（前端判空，不显示点赞态）
            return;
        }
        Double score = stringRedisTemplate.opsForZSet()
                .score(ATTRACTION_LIKED_KEY + attraction.getId(), currentUser.getId().toString());
        attraction.setIsLike(score != null);
    }

    /**
     * 【收藏功能的实现】补充景点收藏状态与收藏数（详情返回）
     * <p>
     * 实现细节：
     * - favoriteCount：实时 COUNT(tb_favorite WHERE target_type='attraction' AND target_id=?)，
     *   收藏/取消收藏均实时落库且无缓存，直接统计即最新值。
     * - isFavorite：与 isLike 同模式——仅登录用户判断（UserHolder 判空），未登录保持 null；
     *   COUNT(tb_favorite WHERE user_id=? AND target_type='attraction' AND target_id=?) > 0 即已收藏。
     * - 通过 FavoriteMapper 直查避免注入 IFavoriteService 造成与 FavoriteServiceImpl 循环依赖。
     * @param attraction 景点实体（写 isFavorite/favoriteCount 非表字段）
     */
    private void attachFavoriteInfo(Attraction attraction) {
        // 1.收藏数实时统计（不依赖缓存）
        Long favoriteCount = favoriteMapper.selectCount(
                new QueryWrapper<Favorite>()
                        .eq("target_type", "attraction")
                        .eq("target_id", attraction.getId()));
        attraction.setFavoriteCount(favoriteCount == null ? 0 : favoriteCount.intValue());
        // 2.是否收藏：仅登录用户判断（游客不查，isFavorite 保持 null）
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return;
        }
        Long favoriteCountByUser = favoriteMapper.selectCount(
                new QueryWrapper<Favorite>()
                        .eq("user_id", currentUser.getId())
                        .eq("target_type", "attraction")
                        .eq("target_id", attraction.getId()));
        attraction.setIsFavorite(favoriteCountByUser != null && favoriteCountByUser > 0);
    }

    /**
     * 【动态评分的实现】补充景点动态均分/评分人数/当前用户打分（详情返回）
     * <p>
     * 实现细节：
     * - avgScore：实时 AVG(tb_attraction_rating.score)（selectObjs 取首列聚合值，JDBC 返回 BigDecimal，
     *   统一按 Number 转 double）。**无评分（聚合为 null）时回退景点原 score**（tb_attraction.score，×10 存储）。
     *   量纲约定：原 score 以 ×10 存储（前端 /10 展示），动态均分同样乘 10 并保留 1 位小数，
     *   保证"有评分均分"与"无评分回退"两种取值同量纲、前端展示口径一致（评分 1~5 → 均分 ×10 后前端 /10）。
     * - ratingCount：实时 COUNT 该景点评分记录（=参与打分人数），前端可展示"xx人评分"。
     * - myRating：仅登录用户判断（UserHolder 判空）——查 tb_attraction_rating 中该用户对该景点的打分
     *   （1~5），未登录或未打分保持 null；与 isLike/isFavorite 的"未登录不渲染"策略一致。
     * @param attraction 景点实体（写 avgScore/ratingCount/myRating 非表字段）
     */
    private void attachRatingInfo(Attraction attraction) {
        // 1.动态均分：AVG(score)，无评分回退原 score（×10 量纲统一）
        List<Object> avgList = ratingMapper.selectObjs(
                new QueryWrapper<AttractionRating>().select("AVG(score)").eq("attraction_id", attraction.getId()));
        if (avgList != null && !avgList.isEmpty() && avgList.get(0) != null) {
            // 有评分：AVG 聚合值 ×10（保持与 score 同量纲）并保留 1 位小数
            double avg = ((Number) avgList.get(0)).doubleValue();
            attraction.setAvgScore(Math.round(avg * 100) / 10.0);
        } else {
            // 无评分：回退景点原 score（×10 存储，前端 /10 展示）
            attraction.setAvgScore(attraction.getScore() == null ? null : attraction.getScore().doubleValue());
        }
        // 2.评分人数
        Long ratingCount = ratingMapper.selectCount(
                new QueryWrapper<AttractionRating>().eq("attraction_id", attraction.getId()));
        attraction.setRatingCount(ratingCount == null ? 0 : ratingCount.intValue());
        // 3.当前登录用户自己的打分（未登录/未打分保持 null）
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return;
        }
        AttractionRating mine = ratingMapper.selectOne(new QueryWrapper<AttractionRating>()
                .eq("user_id", currentUser.getId())
                .eq("attraction_id", attraction.getId()));
        attraction.setMyRating(mine == null ? null : mine.getScore());
    }

    /**
     * 【打卡与足迹的实现】补充当前用户打卡态与景点打卡总人数（详情返回）
     * <p>
     * 实现细节：
     * - checkinCount：实时 COUNT(tb_attraction_checkin WHERE attraction_id=?)，即该景点"来过"统计
     *   （打卡=来过，一人一景点一条，由 uk_ac(user_id, attraction_id) 唯一键保证）。
     * - checkedIn：仅登录用户判断（UserHolder 判空）——COUNT(user_id=? AND attraction_id=?) > 0 即已打卡，
     *   未登录保持 null（与 isLike/isFavorite 策略一致）。
     * @param attraction 景点实体（写 checkedIn/checkinCount 非表字段）
     */
    private void attachCheckinInfo(Attraction attraction) {
        // 1.打卡总人数（来过统计）
        Long checkinCount = checkinMapper.selectCount(
                new QueryWrapper<AttractionCheckin>().eq("attraction_id", attraction.getId()));
        attraction.setCheckinCount(checkinCount == null ? 0 : checkinCount.intValue());
        // 2.当前登录用户是否已打卡（未登录保持 null）
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return;
        }
        Long count = checkinMapper.selectCount(new QueryWrapper<AttractionCheckin>()
                .eq("user_id", currentUser.getId())
                .eq("attraction_id", attraction.getId()));
        attraction.setCheckedIn(count != null && count > 0);
    }

    /**
     * 【景点评论的实现】评论数由评论表 COUNT 实时统计（覆盖种子静态 comments 字段）
     * <p>
     * 实现细节：tb_attraction.comments 为种子静态热度字段，发表评论时**不维护**该计数（简化设计，
     * 见 AttractionCommentServiceImpl 注释）；为保证详情返回的 comments 与评论表真实数据一致，
     * 这里实时 COUNT(tb_attraction_comment WHERE attraction_id=?) 覆盖写入，前端展示的评论数
     * = 评论表条数（含 seed 评论），不会因缺同步而失真。
     * @param attraction 景点实体（写 comments 字段）
     */
    private void attachCommentCount(Attraction attraction) {
        Long count = commentMapper.selectCount(
                new QueryWrapper<AttractionComment>().eq("attraction_id", attraction.getId()));
        attraction.setComments(count == null ? 0 : count.intValue());
    }

    /**
     * 【门票信息的实现】补充景点最低门票价（详情返回，与列表"¥X 起"同源）
     * <p>
     * 实现细节：实时 MIN(tb_ticket.pay_value WHERE attraction_id=?)——金额单位分（前端 fenToYuan /100 展示），
     * 与 /ticket/list/{attractionId} 门票列表的 pay_value 同源（数据源均=tb_ticket），**覆盖 DB 静态
     * avg_price 列**（预置冗余值随门票数据变更而失真，不再作为展示源），保证详情页头部价格与门票列表
     * 最低价一致、进而与列表页"¥X 起"一致；无票返回 0（前端显示"免费"）。
     * @param attraction 景点实体（写 avgPrice 非表展示语义字段）
     */
    private void attachTicketPrice(Attraction attraction) {
        List<Object> minList = ticketMapper.selectObjs(
                new QueryWrapper<Ticket>().select("MIN(pay_value)").eq("attraction_id", attraction.getId()));
        if (minList != null && !minList.isEmpty() && minList.get(0) != null) {
            attraction.setAvgPrice(((Number) minList.get(0)).longValue());
        } else {
            // 无票：0（前端显示"免费"）
            attraction.setAvgPrice(0L);
        }
    }

    /**
     * 【列表动态评分与打卡统计的实现】批量补充列表景点的动态评分/评分人数/打卡人数与门票信息
     * <p>
     * 实现细节：
     * - 背景：详情接口 /attraction/{id} 已 attach avgScore/ratingCount/checkinCount/checkedIn/myRating，
     *   但列表接口（of/type、of/name、of/destination）原先未 attach → 主页卡片用原 score、详情用
     *   avgScore，评分展示不一致。本方法统一为所有返回 List&lt;Attraction&gt; 的列表接口批量补充，
     *   保证列表与详情动态评分口径一致。
     * - **批量 GROUP BY 防 N+1**：一次性收集全部景点 id 后，评分聚合一次
     *   SELECT attraction_id, AVG(score), COUNT(*) ... GROUP BY attraction_id，打卡聚合一次
     *   SELECT attraction_id, COUNT(*) ... GROUP BY attraction_id，各拿 Map&lt;id, 聚合值&gt;
     *   再逐个回填，绝不对每个景点逐条 COUNT/AVG（避免 N+1 查询拖垮列表页）。
     * - avgScore 与详情同源（一致）：有评分时 AVG(score)*10 保留 1 位小数（与 queryById 的
     *   attachRatingInfo 公式完全一致）；**无评分不覆盖原 score**——avgScore 保持原 score 值
     *   （×10 同量纲），前端有 avgScore 优先用之、无则回退原 score，保证"有评分的景点
     *   列表卡片与详情显示同一均分"。
 * - checkinCount 供"人来过"：实时 COUNT(tb_attraction_checkin) 分组值，无打卡默认 0。
 * - 【门票信息的实现】hasTicket/avgPrice/hasSeckill：有 tb_ticket=收费（hasTicket=true）、
 *   无票=免费（hasTicket=false，avgPrice=0 前端显示"免费"）；**avgPrice=该景点所有门票的
 *   最低 pay_value（分）**，一次 MIN(pay_value) GROUP BY + IN 取全量（防 N+1），与详情
 *   门票列表（/ticket/list/{attractionId} 的 pay_value）同源，列表"¥X 起"与详情价格一致；
 *   有秒杀票（tb_seckill_ticket JOIN tb_ticket）=秒杀活动（hasSeckill=true）。
 * @param attractions 列表景点（原地补充 avgScore/ratingCount/checkinCount/avgPrice/hasTicket/hasSeckill）
     */
    private void attachListInfo(List<Attraction> attractions) {
        // 0.空列表直接返回
        if (attractions == null || attractions.isEmpty()) {
            return;
        }
        // 1.收集景点 id 列表（用于批量 IN 查询）
        List<Long> ids = new ArrayList<>(attractions.size());
        for (Attraction attraction : attractions) {
            if (attraction.getId() != null) {
                ids.add(attraction.getId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        // 2.【列表动态评分与打卡统计的实现】批量聚合查询（GROUP BY + IN，防 N+1）
        // 2.1 评分聚合：一次 AVG(score)+COUNT(*) 分组，attraction_id -> {AVG, COUNT}
        Map<Long, double[]> ratingAgg = new HashMap<>(ids.size()); // [0]=AVG(score) [1]=COUNT(*)
        List<Map<String, Object>> ratingRows = ratingMapper.selectMaps(
                new QueryWrapper<AttractionRating>()
                        .select("attraction_id", "AVG(score) AS avgScore", "COUNT(*) AS ratingCount")
                        .in("attraction_id", ids)
                        .groupBy("attraction_id"));
        if (ratingRows != null) {
            for (Map<String, Object> row : ratingRows) {
                Object attractionIdObj = row.get("attraction_id");
                if (attractionIdObj == null) {
                    continue;
                }
                Long attractionId = ((Number) attractionIdObj).longValue();
                double avg = row.get("avgScore") == null ? 0 : ((Number) row.get("avgScore")).doubleValue();
                double count = row.get("ratingCount") == null ? 0 : ((Number) row.get("ratingCount")).doubleValue();
                ratingAgg.put(attractionId, new double[]{avg, count});
            }
        }
        // 2.2 打卡聚合：一次 COUNT(*) 分组，attraction_id -> 打卡人数（来过统计）
        Map<Long, Integer> checkinAgg = new HashMap<>(ids.size());
        List<Map<String, Object>> checkinRows = checkinMapper.selectMaps(
                new QueryWrapper<AttractionCheckin>()
                        .select("attraction_id", "COUNT(*) AS checkinCount")
                        .in("attraction_id", ids)
                        .groupBy("attraction_id"));
        if (checkinRows != null) {
            for (Map<String, Object> row : checkinRows) {
                Object attractionIdObj = row.get("attraction_id");
                if (attractionIdObj == null) {
                    continue;
                }
                Long attractionId = ((Number) attractionIdObj).longValue();
                Object countObj = row.get("checkinCount");
                checkinAgg.put(attractionId, countObj == null ? 0 : ((Number) countObj).intValue());
            }
        }
        // 2.3【门票信息的实现】批量判定免费/门票/秒杀 + 最低门票价：
        //     有 tb_ticket=收费（hasTicket=true，前端展示门票价），无票=免费（hasTicket=false）
        //     avgPrice=该景点所有门票的最低 pay_value（分）：一次 MIN(pay_value) GROUP BY + IN 取全量，
        //     与详情门票列表（/ticket/list/{attractionId} 的 pay_value）同源，列表"¥X 起"与详情价格一致
        //     有秒杀票（tb_seckill_ticket JOIN tb_ticket）=秒杀活动（hasSeckill=true）
        Map<Long, Long> ticketMinPrice = new HashMap<>(ids.size()); // attraction_id -> 最低票价（分）
        List<Map<String, Object>> ticketRows = ticketMapper.selectMaps(
                new QueryWrapper<Ticket>()
                        .select("attraction_id", "MIN(pay_value) AS minPrice")
                        .in("attraction_id", ids)
                        .groupBy("attraction_id"));
        if (ticketRows != null) {
            for (Map<String, Object> row : ticketRows) {
                Object attractionIdObj = row.get("attraction_id");
                if (attractionIdObj == null) {
                    continue;
                }
                Long attractionId = ((Number) attractionIdObj).longValue();
                Object minPriceObj = row.get("minPrice");
                ticketMinPrice.put(attractionId, minPriceObj == null ? 0L : ((Number) minPriceObj).longValue());
            }
        }
        Set<Long> seckillAttractionIds = new HashSet<>(seckillTicketMapper.selectAttractionIdsWithSeckill(ids));

        // 3.逐个回填（avgScore 与详情同源；无评分不覆盖原 score，前端回退显示原 score）
        for (Attraction attraction : attractions) {
            Long id = attraction.getId();
            double[] rating = ratingAgg.get(id);
            if (rating != null) {
                // 有评分：AVG×10 保留 1 位小数（与详情 attachRatingInfo 同源，列表/详情一致）
                attraction.setAvgScore(Math.round(rating[0] * 100) / 10.0);
                attraction.setRatingCount((int) rating[1]);
            } else {
                // 无评分：不覆盖原 score——avgScore 保持原 score 值，前端有 avgScore 用之、无则回退原 score
                attraction.setAvgScore(attraction.getScore() == null ? null : attraction.getScore().doubleValue());
                attraction.setRatingCount(0);
            }
            // 打卡人数默认 0（供"人来过"展示）
            attraction.setCheckinCount(checkinAgg.getOrDefault(id, 0));
            // 门票信息：无票景点 hasTicket=false、avgPrice=0（前端显示"免费"）；
            // 有票 avgPrice=最低门票价（分，与详情门票列表同源，列表"¥X 起"与详情价格一致）；
            // hasSeckill 仅秒杀票命中才为 true
            boolean hasTicket = ticketMinPrice.containsKey(id);
            attraction.setHasTicket(hasTicket);
            attraction.setHasSeckill(seckillAttractionIds.contains(id));
            attraction.setAvgPrice(hasTicket ? ticketMinPrice.get(id) : 0L);
        }
    }

    /**
     * 【动态评分的实现】用户给景点打分（1~5），唯一约束 upsert（先查后插/改，用户可改分）
     * <p>
     * 实现细节：
     * - 需登录：POST /attraction/rate 与匿名路径 /attraction/* 匹配冲突（未被拦截器拦截），
     *   因此本方法开头 UserHolder 判空兜底 fail"请先登录"（与 likeAttraction 双保险模式一致）。
     * - 参数校验：score 非 1~5 整数 fail"评分需为1~5"；景点不存在 fail"景点不存在"。
     * - upsert 语义：先按 (user_id, attraction_id) 查 tb_attraction_rating（uk_ar 唯一键保证最多一条）——
     *   查不到则 INSERT 新评分（一人一票首次落库）；查得到则 UPDATE score（**用户可改分**，覆盖旧分）。
     * - 打分只写评分表，动态均分由查询时 AVG 实时计算（见 attachRatingInfo），无需缓存/计数同步。
     * @param attractionId 景点id
     * @param score        评分 1~5
     * @return 打分成功返回 ok
     */
    @Override
    public Result rate(Long attractionId, Integer score) {
        // 1.获取登录用户（判空兜底：/attraction/rate 与 /attraction/* 匿名匹配冲突，未拦截时靠这里拦）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        // 2.校验评分范围 1~5
        if (score == null || score < 1 || score > 5) {
            return Result.fail("评分需为1~5");
        }
        // 3.校验景点存在
        if (attractionId == null || getById(attractionId) == null) {
            return Result.fail("景点不存在");
        }
        // 4.唯一约束 upsert：先查该用户是否已打分（uk_ar 唯一键保证最多一条）
        AttractionRating rating = ratingMapper.selectOne(new QueryWrapper<AttractionRating>()
                .eq("user_id", user.getId())
                .eq("attraction_id", attractionId));
        if (rating == null) {
            // 4.1.首次打分：插入（一人一票）
            rating = new AttractionRating();
            rating.setAttractionId(attractionId);
            rating.setUserId(user.getId());
            rating.setScore(score);
            ratingMapper.insert(rating);
        } else {
            // 4.2.已打分：修改分数（用户可改分，覆盖旧分）
            rating.setScore(score);
            ratingMapper.updateById(rating);
        }
        return Result.ok();
    }

    /**
     * 【动态评分的实现】查询某用户对某景点的打分（详情 myRating 的独立查询入口，可选接口）
     * <p>
     * 实现细节：
     * - 需登录：GET /attraction/rate/status（路径两段，未被 /attraction/* 放行）被 LoginInterceptor 拦截，
     *   未登录返回 401；service 仍 UserHolder 判空兜底。
     * - userId 可选：缺省取当前登录用户（本人评分查询最常用）；传入则查该用户对该景点的打分。
     * - 返回：data=1~5 整数；未打分返回 ok 且 data 为 null（与 /attraction/{id} 详情 myRating 取值一致）。
     * @param attractionId 景点id
     * @param userId       用户id（可选，缺省=当前登录用户）
     * @return data=打分 1~5；未打分为 null
     */
    @Override
    public Result rateStatus(Long attractionId, Long userId) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        Long targetUserId = userId != null ? userId : currentUser.getId();
        AttractionRating rating = ratingMapper.selectOne(new QueryWrapper<AttractionRating>()
                .eq("user_id", targetUserId)
                .eq("attraction_id", attractionId));
        if (rating == null) {
            return Result.ok();
        }
        return Result.ok(rating.getScore());
    }

    /**
     * 【打卡与足迹的实现】用户打卡某景点（唯一约束幂等：已打卡返回 ok 不重复计数）
     * <p>
     * 实现细节：
     * - 需登录：POST /attraction/checkin/{id}（路径两段，未被 /attraction/* 放行）被 LoginInterceptor
     *   拦截，未登录返回 401；service 仍 UserHolder 判空兜底。
     * - 校验：景点不存在 fail"景点不存在"。
     * - 幂等：先按 (user_id, attraction_id) COUNT——已打卡直接返回 ok（唯一键 uk_ac 保证不重复落库，
     *   重复打卡不重复计数）；未打卡则 INSERT 一条打卡记录。
     * - 语义：打卡记录 = 用户足迹来源（/user/checkins/{id} 按时间倒序=足迹列表）；该景点打卡 COUNT =
     *   "来过"统计（checkinCount，见 attachCheckinInfo）。
     * @param id 景点id
     * @return 打卡成功（或已打卡）返回 ok
     */
    @Override
    public Result checkin(Long id) {
        // 1.获取登录用户（判空兜底）
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        // 2.校验景点存在
        if (id == null || getById(id) == null) {
            return Result.fail("景点不存在");
        }
        // 3.唯一约束幂等：已打卡直接返回 ok，不重复计数
        Long count = checkinMapper.selectCount(new QueryWrapper<AttractionCheckin>()
                .eq("user_id", user.getId())
                .eq("attraction_id", id));
        if (count != null && count > 0) {
            return Result.ok();
        }
        // 4.插入打卡记录（足迹来源）
        AttractionCheckin checkin = new AttractionCheckin();
        checkin.setAttractionId(id);
        checkin.setUserId(user.getId());
        checkinMapper.insert(checkin);
        return Result.ok();
    }

    /**
     * 【城市推荐景点的实现】去重目的地城市列表（按景点数倒序）
     * <p>
     * 实现细节：SELECT destination FROM tb_attraction GROUP BY destination ORDER BY COUNT(*) DESC，
     * 统计各城市景点数并按景点数倒序（景点多的城市优先展示）；返回 List&lt;String&gt; 城市名，
     * destination 为空的记录跳过；demo 数据下杭州(20+)、厦门(6) 两城。
     * @return 城市列表（按景点数倒序）
     */
    @Override
    public Result queryDestinations() {
        QueryWrapper<Attraction> wrapper = new QueryWrapper<>();
        wrapper.select("destination").groupBy("destination").orderByDesc("COUNT(*)");
        List<Attraction> list = list(wrapper);
        List<String> destinations = new ArrayList<>(list.size());
        for (Attraction attraction : list) {
            if (StrUtil.isNotBlank(attraction.getDestination())) {
                destinations.add(attraction.getDestination());
            }
        }
        return Result.ok(destinations);
    }

    /**
     * 【景点按目的地查询的实现】按目的地（城市）分页查询，支持叠加类型筛选/排除筛选
     * <p>
     * 实现细节：
     * - city 支持逗号分隔的多城市（如 "杭州,南京"，首页城市选择器最多选 5 城）
     * - typeId 可选（首页城市模式类型 tab）：null=全部类型；非空时单城/多城两分支均叠加 eq(type_id)
     * - excludeTypeIds 可选（首页「其他」聚合 tab）：非空时两分支均叠加 notIn(type_id)，
     *   排除指定类型（如前 2 个常显类型），返回其余全部类型；tb_attraction.type_id 非空列，notIn 语义安全
     * - 单城市/全部：IN 等值分页（与原 eq 版本行为一致）
     * - 多城市：先按城分查（各取前 N 条按销量倒序），再内存轮转穿插合并（杭1南1杭2南2…），
     *   保证每个选中城市在首屏都有露出，避免"销量高的城市霸榜、其他城市翻几页才出现"；
     *   合并结果按 MAX_PAGE_SIZE 切片模拟分页
     * - 返回前经 attachListInfo 批量补充动态评分/打卡数/门票信息（口径与详情一致）
     * @param city           目的地城市（支持逗号分隔多值，可选，空则查全部）
     * @param current        页码（从1开始）
     * @param typeId         景点类型id（可选，null=全部类型）
     * @param excludeTypeIds 排除的类型id集合（可选，空=不排除）
     * @return 景点列表
     */
    @Override
    public Result queryByDestination(String city, Integer current, Integer typeId, List<Long> excludeTypeIds) {
        // 多城市支持："杭州,南京" → IN("杭州","南京")；StrUtil.splitTrim 同时去空白与空串
        List<String> cities = StrUtil.isBlank(city)
                ? Collections.emptyList()
                : StrUtil.splitTrim(city, ',');
        int pageSize = SystemConstants.MAX_PAGE_SIZE;
        // 【首页「其他」聚合 tab 的实现】排除类型非空时叠加 notIn(type_id)
        boolean hasExclude = excludeTypeIds != null && !excludeTypeIds.isEmpty();

        if (cities.size() <= 1) {
            // 单城市 / 全部城市：原分页逻辑（叠加可选类型/排除过滤）
            Page<Attraction> page = query()
                    .in(!cities.isEmpty(), "destination", cities)
                    .eq(typeId != null, "type_id", typeId)
                    .notIn(hasExclude, "type_id", excludeTypeIds)
                    .page(new Page<>(current, pageSize));
            attachListInfo(page.getRecords());
            return Result.ok(page.getRecords());
        }

        // 多城市：每城分查（按销量倒序，取足够切片的量），内存轮转穿插（叠加可选类型/排除过滤）
        int fetchPerCity = Math.min(cities.size() * pageSize, 200);
        List<List<Attraction>> perCity = new ArrayList<>(cities.size());
        for (String c : cities) {
            perCity.add(query()
                    .eq("destination", c)
                    .eq(typeId != null, "type_id", typeId)
                    .notIn(hasExclude, "type_id", excludeTypeIds)
                    .orderByDesc("sold")
                    .last("limit " + fetchPerCity)
                    .list());
        }
        List<Attraction> merged = new ArrayList<>();
        int maxLen = 0;
        for (List<Attraction> l : perCity) {
            maxLen = Math.max(maxLen, l.size());
        }
        for (int i = 0; i < maxLen; i++) {
            for (List<Attraction> l : perCity) {
                if (i < l.size()) {
                    merged.add(l.get(i));
                }
            }
        }
        // 按 current 切片模拟分页
        int from = (current - 1) * pageSize;
        List<Attraction> pageItems = from >= merged.size()
                ? Collections.emptyList()
                : new ArrayList<>(merged.subList(from, Math.min(from + pageSize, merged.size())));
        attachListInfo(pageItems);
        return Result.ok(pageItems);
    }

    /**
     * 【景点按名称查询的实现】按景点名称关键字分页查询（name 为空则全量分页）
     * <p>
     * 实现细节：name 非空按 name LIKE 模糊匹配（与 /attraction/of/name 原 Controller 直查逻辑一致），
     * 每页 SystemConstants.MAX_PAGE_SIZE 条；返回前经 attachListInfo 批量补充动态评分/打卡数/
     * 门票信息（avgScore 与详情同源，修复"主页卡片原 score vs 详情 avgScore 不一致"）。
     * @param name    景点名称关键字（可选，空则查全部）
     * @param current 页码（从1开始）
     * @return 景点列表
     */
    @Override
    public Result queryAttractionByName(String name, Integer current) {
        // 根据名称模糊分页查询（原 Controller 直查逻辑迁移到 service，统一走 attachListInfo）
        Page<Attraction> page = query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 【列表动态评分与打卡统计的实现】列表统一批量补充动态评分/打卡数/门票信息
        attachListInfo(page.getRecords());
        return Result.ok(page.getRecords());
    }

    /**
     * 【景点更新的实现】更新景点并删除缓存（Cache Aside）
     * <p>
     * 实现细节：id 为空直接 fail；@Transactional 先 updateById 落库，成功后
     * stringRedisTemplate.delete(CACHE_ATTRACTION_KEY + id) 删除对应缓存，
     * 下次 queryById 重新回源 DB 并重建缓存，保证读到最新数据（先删缓存而非更新缓存，避免双写不一致）。
     * @param attraction 待更新景点（id 必填）
     * @return 更新成功返回 ok；id 为空 fail
     */
    @Override
    @Transactional
    public Result update(Attraction attraction) {
        Long id = attraction.getId();
        if (id == null) {
            return Result.fail("景点id不能为空");
        }
        // 1.更新数据库
        updateById(attraction);
        // 2.删除缓存
        stringRedisTemplate.delete(CACHE_ATTRACTION_KEY + id);
        return Result.ok();
    }

    /**
     * 【景点按类型查询的实现】按景点类型分页查询，带坐标时按距离排序（Redis GEO）
     * <p>
     * 实现细节：
     * - 无坐标（x/y 任一为 null）：DB 分页查询，Page + eq(type_id) + DEFAULT_PAGE_SIZE，返回当前页记录。
     * - 带坐标：先算分页边界（from=(current-1)*页大小, end=current*页大小），再对 Redis GEO
     *   key=attraction:geo:{typeId} 执行 GEOSEARCH BYLONLAT (x,y) BYRADIUS 5000m WITHDISTANCE，
     *   一次取到 end 条（含距离），若结果不足 from 说明无下一页返回空列表；skip(from) 截取本页，
     *   得到 景点id 集合 + 距离映射；IN 查 DB + ORDER BY FIELD(id,...) 保持距离顺序，回填 distance 字段。
     * - 限流：接口入口由 Controller 层 @RateLimiter(window=5s, limit=5) 滑动窗口限流，
     *   超限 fail "查询次数过多，请稍后再试"，防止恶意刷取（本方法不含限流逻辑）。
     * @param typeId  景点类型id
     * @param current 页码（从1开始）
     * @param x       经度（可选）
     * @param y       纬度（可选）
     * @return 景点列表（带坐标时含 distance）；无数据返回空列表
     */
    @Override
    public Result queryAttractionByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Attraction> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 【列表动态评分与打卡统计的实现】列表统一批量补充动态评分/打卡数/门票信息
            // （DB 分页分支：与详情同源的 avgScore，修复"主页卡片原 score vs 详情 avgScore 不一致"）
            attachListInfo(page.getRecords());
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：attractionId、distance
        String key = ATTRACTION_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取景点id
            String attractionIdStr = result.getContent().getName();
            ids.add(Long.valueOf(attractionIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(attractionIdStr, distance);
        });
        // 5.根据id查询Attraction
        String idStr = StrUtil.join(",", ids);
        List<Attraction> attractions = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Attraction attraction : attractions) {
            attraction.setDistance(distanceMap.get(attraction.getId().toString()).getValue());
        }
        // 【列表动态评分与打卡统计的实现】列表统一批量补充动态评分/打卡数/门票信息
        // （GEO 分支：与 DB 分支同一 attachListInfo，评分口径与详情一致）
        attachListInfo(attractions);
        // 6.返回
        return Result.ok(attractions);
    }
}
