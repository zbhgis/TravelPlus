package com.travelplus.service.impl;

import cn.hutool.core.util.StrUtil;
import com.travelplus.dto.Result;
import com.travelplus.entity.Attraction;
import com.travelplus.entity.TravelNote;
import com.travelplus.mapper.AttractionMapper;
import com.travelplus.mapper.TravelNoteMapper;
import com.travelplus.service.IBrowseRecordService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.travelplus.utils.RedisConstants.BROWSE_RECORD_KEY;
import static com.travelplus.utils.RedisConstants.BROWSE_RECORD_MAX_SIZE;

/**
 * <p>
 * 【浏览记录的实现】浏览记录服务实现：Redis ZSet 存储，免建表，读写风格与签到/点赞一致
 * </p>
 * <p>
 * 实现细节：
 * - 为什么用 Redis ZSet 而不是 MySQL：① 与签到 BitMap、点赞 Set 同属"个人轻量行为数据"，Redis 原生
 *   有序集合天然支持"按时间倒序取最近 N 条"（ZREVRANGE），免去建表/索引/清理脚本；
 *   ② member 唯一 + score=时间戳 的组合天然实现"去重 + 最近浏览时间"两个需求；
 *   ③ 浏览记录可配置过期（redis.conf maxmemory 或业务侧 TTL），不会无限增长占用磁盘。
 * - key 约定：browse:{userId}；member 格式：note:{id} / attraction:{id}（type 与 id 冒号分隔，
 *   保证不同类型同 id 的对象互不冲突）。
 * - 去重原理：ZADD 同一 member 只保留一份，但 score 更新为最新时间戳——重复浏览同一条记录不会产生
 *   重复条目，只会把它"顶到最前"（score 最大=最近浏览）。
 * - 上限控制：写入后裁剪超过 50 条的最早记录（ZREMRANGEBYRANK key 0 -51 的语义：当条数 > 50 时，
 *   删除从最旧到第 51 新的区间，恰好保留最近 50 条；条数不足 51 时负索引会命中最旧区间导致全删，
 *   故先 zCard 判大小再执行，避免误删）。
 * </p>
 */
@Service
public class BrowseRecordServiceImpl implements IBrowseRecordService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 直接注入 Mapper 而非 ITravelNoteService/IAttractionService：
     * 两个 Service 实现类都会注入 IBrowseRecordService（记录浏览），再注入它们会形成循环依赖。
     */
    @Resource
    private TravelNoteMapper noteMapper;

    @Resource
    private AttractionMapper attractionMapper;

    /**
     * 【浏览记录的实现】记录一次浏览行为（Redis ZSet 去重 + 最近浏览时间 + 上限裁剪）
     * <p>
     * 实现细节：
     * - 写入：ZADD key=browse:{userId} member=type:id（note:{id} / attraction:{id}）
     *   score=System.currentTimeMillis()；同一 member 只保留一份且 score 更新为最近时间，
     *   重复浏览自动"顶到最前"不产生重复条目。
     * - 上限：写入后 zCard 判大小，超过 BROWSE_RECORD_MAX_SIZE（50）时
     *   ZREMRANGEBYRANK key 0 -51 删除最旧的超量部分（先判大小再删，避免不足 51 条时负索引误删全部）。
     * @param userId 浏览者（登录用户）id
     * @param type   浏览对象类型：note=游记，attraction=景点
     * @param id     浏览对象id
     */
    @Override
    public void record(Long userId, String type, Long id) {
        // 1.写 ZSet：member=type:id，score=当前毫秒时间戳（member 唯一，重复浏览只更新 score=最近浏览时间）
        String key = BROWSE_RECORD_KEY + userId;
        String member = type + ":" + id;
        stringRedisTemplate.opsForZSet().add(key, member, System.currentTimeMillis());
        // 2.上限控制：仅当记录数超过上限（50）时，删除最旧的超量部分（ZREMRANGEBYRANK key 0 -51）
        Long size = stringRedisTemplate.opsForZSet().zCard(key);
        if (size != null && size > BROWSE_RECORD_MAX_SIZE) {
            // rank 从 0（最旧）起算，-BROWSE_RECORD_MAX_SIZE - 1 = -51 恰好保留最近 50 条
            stringRedisTemplate.opsForZSet().removeRange(key, 0, -BROWSE_RECORD_MAX_SIZE - 1);
        }
    }

    /**
     * 【浏览记录的实现】查询某用户最近浏览的记录列表（按浏览时间倒序）
     * <p>
     * 实现细节：
     * - 读取：ZREVRANGE key 0 49 取最近 50 条 member+score（时间倒序，score=浏览毫秒时间戳）。
     * - 解析：member 形如 note:{id}/attraction:{id}，按类型分别收集 id 并记录 member→time 映射；
     *   非法 member / 非数字 id 的脏数据跳过。
     * - 详情回填：note 批量查 title+images 首图，attraction 批量查 name+images 首图（selectBatchIds 一次查询）；
     *   已删除的对象（查不到）对应记录直接跳过，不返回死链。
     * - 组装：按 ZSet 时间倒序组装 {type, id, title, image, time} 列表返回。
     * @param userId 用户id
     * @return Result.data=List&lt;Map&gt;：{type, id, title, image, time}，按浏览时间倒序
     */
    @Override
    public Result list(Long userId) {
        String key = BROWSE_RECORD_KEY + userId;
        // 1.取最近 50 个 member 及对应 score（ZREVRANGE 时间倒序，score=浏览时间戳）
        Set<ZSetOperations.TypedTuple<String>> tuples = stringRedisTemplate.opsForZSet()
                .reverseRangeWithScores(key, 0, BROWSE_RECORD_MAX_SIZE - 1);
        if (tuples == null || tuples.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        // 2.解析 member（note:{id} / attraction:{id}），按类型收集 id 并记录 member→time 映射
        List<Long> noteIds = new ArrayList<>();
        List<Long> attractionIds = new ArrayList<>();
        Map<String, Long> timeMap = new HashMap<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String member = tuple.getValue();
            String[] parts = member.split(":", 2);
            if (parts.length != 2) {
                // 非法的 member 跳过
                continue;
            }
            try {
                Long bizId = Long.valueOf(parts[1]);
                if ("note".equals(parts[0])) {
                    noteIds.add(bizId);
                } else if ("attraction".equals(parts[0])) {
                    attractionIds.add(bizId);
                } else {
                    // 未知类型跳过
                    continue;
                }
                timeMap.put(member, tuple.getScore().longValue());
            } catch (NumberFormatException e) {
                // id 不是数字的脏数据跳过
            }
        }
        // 3.批量查询详情：note 取 title + images 首图，attraction 取 name + images 首图
        Map<Long, String> titleMap = new HashMap<>();
        Map<Long, String> imageMap = new HashMap<>();
        if (!noteIds.isEmpty()) {
            List<TravelNote> notes = noteMapper.selectBatchIds(noteIds);
            for (TravelNote note : notes) {
                titleMap.put(note.getId(), note.getTitle());
                imageMap.put(note.getId(), firstImage(note.getImages()));
            }
        }
        if (!attractionIds.isEmpty()) {
            List<Attraction> attractions = attractionMapper.selectBatchIds(attractionIds);
            for (Attraction attraction : attractions) {
                titleMap.put(attraction.getId(), attraction.getName());
                imageMap.put(attraction.getId(), firstImage(attraction.getImages()));
            }
        }
        // 4.按 ZSet 时间倒序组装返回；查不到的 member（对象被删除）直接跳过
        List<Map<String, Object>> result = new ArrayList<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            String member = tuple.getValue();
            String[] parts = member.split(":", 2);
            if (parts.length != 2) {
                continue;
            }
            Long bizId;
            try {
                bizId = Long.valueOf(parts[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (!titleMap.containsKey(bizId)) {
                // 对象已不存在，跳过该条浏览记录
                continue;
            }
            Map<String, Object> item = new HashMap<>();
            item.put("type", parts[0]);
            item.put("id", bizId);
            item.put("title", titleMap.get(bizId));
            item.put("image", imageMap.get(bizId));
            item.put("time", timeMap.get(member));
            result.add(item);
        }
        return Result.ok(result);
    }

    /**
     * 取逗号分隔图片串的第一张图（首图作列表封面）
     */
    private String firstImage(String images) {
        if (StrUtil.isBlank(images)) {
            return "";
        }
        return images.split(",")[0];
    }
}
