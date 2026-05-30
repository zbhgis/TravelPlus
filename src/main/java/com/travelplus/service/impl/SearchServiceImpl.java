package com.travelplus.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.entity.Attraction;
import com.travelplus.entity.TravelNote;
import com.travelplus.entity.User;
import com.travelplus.es.AttractionDoc;
import com.travelplus.es.NoteDoc;
import com.travelplus.es.UserDoc;
import com.travelplus.mapper.AttractionMapper;
import com.travelplus.mapper.TravelNoteMapper;
import com.travelplus.mapper.UserMapper;
import com.travelplus.service.ISearchService;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.StringQuery;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 【全局搜索的实现】跨 景点/游记/用户 三类数据的全局搜索实现（公开接口）。
 * <p>
 * 实现要点：
 * - 【Elasticsearch搜索的实现】搜索主路径改为 ES：三类分别 match_phrase 查询
 *   attraction_es.name / note_es.title / user_es.nickName（standard 分词中文按字切分，
 *   match_phrase 等价 LIKE '%keyword%' 连续子串命中），每类最多 10 条，返回结构与旧 LIKE 完全一致。
 * - 【Elasticsearch搜索的实现】降级策略：ES 不可用/查询异常 → catch 后回退原 MyBatis-Plus LIKE 实现，
 *   ES 挂了搜索仍可用（只是慢）；travelplus.es.enabled=false 时直接走 LIKE，不初始化 ES。
 * - 首图取 images 逗号分隔第 1 项（ES 文档同步时已算好）；score 原值返回（前端 /10 展示）。
 * - keyword 为空直接返回三类空列表（不查库/不查 ES）。
 * - 热门词条为演示固定常量（不建表）；联想为三类 top3 混合去重最多 6 条。
 * </p>
 *
 * @author TravelPlus
 */
@Slf4j
@Service
public class SearchServiceImpl implements ISearchService {

    /** 【全局搜索的实现】联想词每类取前 3 条（景点名/游记标题/用户昵称各 3，混合去重后最多 6 条） */
    private static final int SUGGEST_PER_TYPE = 3;
    /** 【全局搜索的实现】联想词混合去重后的最大返回条数 */
    private static final int SUGGEST_MAX = 6;

    /**
     * 【搜索词条推荐的实现】演示用固定热门搜索词条（8~10 个，与平台景点/目的地相关）。
     * <p>
     * 实现细节：不建搜索词表（不落库），直接返回常量数组，注释说明演示用；
     * 后续可扩展为按搜索频次统计（如 keyword 写入 Redis ZSet 计数取 Top N）替换本常量。
     * </p>
     */
    private static final List<String> HOT_KEYWORDS = new ArrayList<>(List.of(
            "杭州", "西湖", "断桥", "灵隐寺", "鼓浪屿", "乌镇", "千岛湖", "厦门", "龙井", "雷峰塔"));

    @Resource
    private AttractionMapper attractionMapper;
    @Resource
    private TravelNoteMapper travelNoteMapper;
    @Resource
    private UserMapper userMapper;
    /** 【Elasticsearch搜索的实现】ES 操作入口（spring-data-elasticsearch 自动装配），查询异常由 catch 降级 LIKE */
    @Resource
    private ElasticsearchOperations operations;
    /** 【Elasticsearch搜索的实现】ES 功能开关：false 时 /search 直接走 LIKE（对应 application.yaml travelplus.es.enabled） */
    @Value("${travelplus.es.enabled:true}")
    private boolean esEnabled;

    /**
     * 【全局搜索的实现】跨三类数据全局搜索（公开接口）
     * <p>
     * 实现细节：
     * - 【Elasticsearch搜索的实现】ES 优先：三类 match_phrase 查询（name/title/nickName），每类 size 10，
     *   组装与旧实现逐字段一致的 {attractions, notes, users}；任一异常 catch 降级 LIKE。
     * - 降级 LIKE：景点 name、游记 title（含可见性过滤）、用户 nick_name 三张表 like 查询 + LIMIT 10。
     * - keyword 为空：三类均返回空列表，不触发查询。
     * </p>
     * @param keyword 搜索关键字（非空才检索）
     * @param current 页码（默认第1页，保留分页语义；简化实现每类固定最多 10 条）
     * @return {attractions, notes, users} 三类结果
     */
    @Override
    public Result search(String keyword, Integer current) {
        // 1.空关键字直接返回三类空结果（不查库）
        if (StrUtil.isBlank(keyword)) {
            Map<String, Object> empty = new LinkedHashMap<>(3);
            empty.put("attractions", Collections.emptyList());
            empty.put("notes", Collections.emptyList());
            empty.put("users", Collections.emptyList());
            return Result.ok(empty);
        }
        // 2.【Elasticsearch搜索的实现】ES 优先查询；异常/开关关闭 → 降级 LIKE（保证系统可用）
        if (esEnabled) {
            try {
                return searchByEs(keyword, current);
            } catch (Exception e) {
                // 降级策略：ES 挂了搜索仍可用，只是慢（22 万行 LIKE 全表扫描约百毫秒级，见 es-baseline.md）
                log.warn("【Elasticsearch搜索的实现】ES 查询失败，降级 LIKE 查询：{}", e.getMessage());
            }
        }
        return searchByLike(keyword, current);
    }

    /**
     * 【Elasticsearch搜索的实现】ES 版全局搜索：三类 match_phrase 查询，返回结构与 LIKE 实现逐字段一致。
     * <p>
     * 实现细节：
     * - 查询构造：StringQuery 内联 JSON（match_phrase 等价 LIKE '%kw%' 连续子串命中，standard 分词中文按字切分）；
     *   每类 size=10（与原 LIMIT 10 一致）。
     * - 游记可见性：bool filter 复刻 SQL "visible=0 OR user_id=当前用户"（游客只搜到公开笔记）。
     * - 结果组装：景点/游记构造与 LIKE 完全相同的 LinkedHashMap（字段名/顺序一致）；
     *   用户构造 UserDTO（仅 id/nickName/icon，与 LIKE 的 BeanUtil.copyProperties 结果一致）。
     * - id 为雪花 Long：ES 存 long 返 Long，与旧 /search 返回类型一致（前端无精度问题）。
     * </p>
     * @param keyword 搜索关键字
     * @param current 页码（保留分页语义，简化实现每类固定最多 10 条）
     * @return 三类搜索结果
     */
    private Result searchByEs(String keyword, Integer current) {
        // 1.景点：attraction_es 上 match_phrase(name)
        // 【Elasticsearch搜索的实现】StringQuery 的 source 是"query 部分"（不含外层 {"query":...} 包裹，
        // 否则 ES 报 parsing_exception unknown query [query]）；每类条数用 Pageable 控制（size=10）。
        Pageable pageable = PageRequest.of(0, SystemConstants.MAX_PAGE_SIZE);
        SearchHits<AttractionDoc> attractionHits = operations.search(
                new StringQuery("{\"match_phrase\":{\"name\":" + esString(keyword) + "}}", pageable),
                AttractionDoc.class, IndexCoordinates.of("attraction_es"));
        List<Map<String, Object>> attractionItems = new ArrayList<>();
        for (SearchHit<AttractionDoc> hit : attractionHits.getSearchHits()) {
            AttractionDoc doc = hit.getContent();
            Map<String, Object> item = new LinkedHashMap<>(6);
            item.put("id", doc.getId());
            item.put("name", doc.getName());
            item.put("image", doc.getImage());
            item.put("score", doc.getScore());
            item.put("avgPrice", doc.getAvgPrice());
            item.put("destination", doc.getDestination());
            attractionItems.add(item);
        }

        // 2.游记：note_es 上 match_phrase(title) + 可见性过滤（visible=0 OR userId=当前用户）
        Long currentUserId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        String noteFilter;
        if (currentUserId == null) {
            noteFilter = "{\"term\":{\"visible\":0}}";
        } else {
            noteFilter = "{\"bool\":{\"should\":[{\"term\":{\"visible\":0}},{\"term\":{\"userId\":"
                    + currentUserId + "}}]}}";
        }
        String noteQuery = "{\"bool\":{\"must\":[{\"match_phrase\":{\"title\":"
                + esString(keyword) + "}}],\"filter\":[" + noteFilter + "]}}";
        SearchHits<NoteDoc> noteHits = operations.search(
                new StringQuery(noteQuery, pageable), NoteDoc.class, IndexCoordinates.of("note_es"));
        List<Map<String, Object>> noteItems = new ArrayList<>();
        for (SearchHit<NoteDoc> hit : noteHits.getSearchHits()) {
            NoteDoc doc = hit.getContent();
            Map<String, Object> item = new LinkedHashMap<>(5);
            item.put("id", doc.getId());
            item.put("title", doc.getTitle());
            item.put("image", doc.getImage());
            item.put("userId", doc.getUserId());
            item.put("name", doc.getName());
            noteItems.add(item);
        }

        // 3.用户：user_es 上 match_phrase(nickName)，构造 UserDTO（仅暴露 id/nickName/icon）
        SearchHits<UserDoc> userHits = operations.search(
                new StringQuery("{\"match_phrase\":{\"nickName\":" + esString(keyword) + "}}", pageable),
                UserDoc.class, IndexCoordinates.of("user_es"));
        List<UserDTO> userItems = new ArrayList<>();
        for (SearchHit<UserDoc> hit : userHits.getSearchHits()) {
            UserDoc doc = hit.getContent();
            UserDTO dto = new UserDTO();
            dto.setId(doc.getId());
            dto.setNickName(doc.getNickName());
            dto.setIcon(doc.getIcon());
            userItems.add(dto);
        }

        // 4.组装三类结果返回
        Map<String, Object> data = new LinkedHashMap<>(3);
        data.put("attractions", attractionItems);
        data.put("notes", noteItems);
        data.put("users", userItems);
        return Result.ok(data);
    }

    /**
     * 【全局搜索的实现】LIKE 降级版全局搜索（ES 不可用/关闭时兜底，行为与原实现完全一致）
     * @param keyword 搜索关键字
     * @param current 页码（保留分页语义；简化实现每类固定最多 10 条）
     * @return 三类搜索结果
     */
    private Result searchByLike(String keyword, Integer current) {
        // 1.景点：name like → {id,name,image,score,avgPrice,destination}（首图取逗号首项，score 原值返回）
        List<Attraction> attractions = attractionMapper.selectList(
                new LambdaQueryWrapper<Attraction>()
                        .like(Attraction::getName, keyword)
                        .last("LIMIT " + SystemConstants.MAX_PAGE_SIZE));
        List<Map<String, Object>> attractionItems = attractions.stream().map(a -> {
            Map<String, Object> item = new LinkedHashMap<>(6);
            item.put("id", a.getId());
            item.put("name", a.getName());
            item.put("image", firstImage(a.getImages()));
            item.put("score", a.getScore());
            item.put("avgPrice", a.getAvgPrice());
            item.put("destination", a.getDestination());
            return item;
        }).collect(Collectors.toList());

        // 2.游记：title like → {id,title,image,userId,name}（首图 + 作者昵称）
        // 【笔记可见性的实现】搜索同样过滤他人隐藏笔记：仅公开笔记（visible=0）或本人自己的笔记，
        // 条件 (visible = 0 OR user_id = 当前登录用户)；游客（未登录）只搜到公开笔记
        Long currentUserId = UserHolder.getUser() == null ? null : UserHolder.getUser().getId();
        List<TravelNote> notes = travelNoteMapper.selectList(
                new LambdaQueryWrapper<TravelNote>()
                        .like(TravelNote::getTitle, keyword)
                        .and(w -> {
                            w.eq(TravelNote::getVisible, 0);
                            if (currentUserId != null) {
                                w.or().eq(TravelNote::getUserId, currentUserId);
                            }
                        })
                        .last("LIMIT " + SystemConstants.MAX_PAGE_SIZE));
        // 批量查作者昵称（按 userId 去重一次查齐，避免逐条查询 N+1）
        Map<Long, String> nickNameMap = queryNickNames(
                notes.stream().map(TravelNote::getUserId).collect(Collectors.toList()));
        List<Map<String, Object>> noteItems = notes.stream().map(n -> {
            Map<String, Object> item = new LinkedHashMap<>(5);
            item.put("id", n.getId());
            item.put("title", n.getTitle());
            item.put("image", firstImage(n.getImages()));
            item.put("userId", n.getUserId());
            item.put("name", nickNameMap.getOrDefault(n.getUserId(), "匿名"));
            return item;
        }).collect(Collectors.toList());

        // 3.用户：nick_name like → UserDTO（拷贝生成，仅暴露 id/nickName/icon）
        List<User> users = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .like(User::getNickName, keyword)
                        .last("LIMIT " + SystemConstants.MAX_PAGE_SIZE));
        List<UserDTO> userItems = users.stream()
                .map(u -> BeanUtil.copyProperties(u, UserDTO.class))
                .collect(Collectors.toList());

        // 4.组装三类结果返回
        Map<String, Object> data = new LinkedHashMap<>(3);
        data.put("attractions", attractionItems);
        data.put("notes", noteItems);
        data.put("users", userItems);
        return Result.ok(data);
    }

    /**
     * 【搜索词条推荐的实现】热门搜索词条（公开接口）
     * <p>
     * 实现细节：演示用固定常量数组返回（杭州/西湖/断桥/灵隐寺/鼓浪屿/乌镇/千岛湖/厦门/龙井/雷峰塔），
     * 不建搜索词表；如需按真实搜索频次推荐，可后续用 Redis ZSet 统计 keyword 出现次数取 Top N 替换。
     * </p>
     * @return 热门搜索词列表
     */
    @Override
    public Result hotKeywords() {
        return Result.ok(HOT_KEYWORDS);
    }

    /**
     * 【全局搜索的实现】输入联想（公开接口）
     * <p>
     * 实现细节：
     * - 【Elasticsearch搜索的实现】ES 优先：三类 match_phrase(name/title/nickName) 各取 top3，
     *   混合去重最多 6 条；异常降级 LIKE。
     * - 降级 LIKE：keyword 非空时 景点名/游记标题/用户昵称 三类分别 like 匹配各取 top3。
     * - 三类结果合并进 LinkedHashSet 去重（重名保留首次出现顺序），再截断为最多 SUGGEST_MAX(6) 条。
     * - 返回扁平 List&lt;String&gt;（前端输入框联想直接渲染字符串）。
     * - keyword 为空返回空列表。
     * </p>
     * @param keyword 输入关键字（非空才联想）
     * @return 联想词列表（最多 6 条）
     */
    @Override
    public Result suggest(String keyword) {
        // 1.空关键字返回空列表
        if (StrUtil.isBlank(keyword)) {
            return Result.ok(Collections.emptyList());
        }
        // 2.【Elasticsearch搜索的实现】ES 优先；异常/开关关闭 → 降级 LIKE
        if (esEnabled) {
            try {
                return suggestByEs(keyword);
            } catch (Exception e) {
                log.warn("【Elasticsearch搜索的实现】ES suggest 失败，降级 LIKE：{}", e.getMessage());
            }
        }
        return suggestByLike(keyword);
    }

    /**
     * 【Elasticsearch搜索的实现】ES 版输入联想：三类 match_phrase 各取 top3，混合去重最多 6 条
     * @param keyword 输入关键字
     * @return 联想词列表（最多 6 条）
     */
    private Result suggestByEs(String keyword) {
        Set<String> merged = new LinkedHashSet<>();
        // 景点名 top3（StringQuery 只传 query 部分，条数用 Pageable 控制）
        Pageable pageable = PageRequest.of(0, SUGGEST_PER_TYPE);
        SearchHits<AttractionDoc> attractionHits = operations.search(
                new StringQuery("{\"match_phrase\":{\"name\":" + esString(keyword) + "}}", pageable),
                AttractionDoc.class, IndexCoordinates.of("attraction_es"));
        attractionHits.getSearchHits().forEach(h -> merged.add(h.getContent().getName()));
        // 游记标题 top3
        SearchHits<NoteDoc> noteHits = operations.search(
                new StringQuery("{\"match_phrase\":{\"title\":" + esString(keyword) + "}}", pageable),
                NoteDoc.class, IndexCoordinates.of("note_es"));
        noteHits.getSearchHits().forEach(h -> merged.add(h.getContent().getTitle()));
        // 用户昵称 top3
        SearchHits<UserDoc> userHits = operations.search(
                new StringQuery("{\"match_phrase\":{\"nickName\":" + esString(keyword) + "}}", pageable),
                UserDoc.class, IndexCoordinates.of("user_es"));
        userHits.getSearchHits().forEach(h -> merged.add(h.getContent().getNickName()));
        // 截断最多 6 条
        List<String> result = new ArrayList<>(merged);
        if (result.size() > SUGGEST_MAX) {
            result = result.subList(0, SUGGEST_MAX);
        }
        return Result.ok(result);
    }

    /**
     * 【全局搜索的实现】LIKE 降级版输入联想（ES 不可用/关闭时兜底）
     * @param keyword 输入关键字
     * @return 联想词列表（最多 6 条）
     */
    private Result suggestByLike(String keyword) {
        // 1.三类各取 top3（like 匹配，LIMIT 截断）
        List<Attraction> attractions = attractionMapper.selectList(
                new LambdaQueryWrapper<Attraction>()
                        .like(Attraction::getName, keyword)
                        .last("LIMIT " + SUGGEST_PER_TYPE));
        List<TravelNote> notes = travelNoteMapper.selectList(
                new LambdaQueryWrapper<TravelNote>()
                        .like(TravelNote::getTitle, keyword)
                        .last("LIMIT " + SUGGEST_PER_TYPE));
        List<User> users = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .like(User::getNickName, keyword)
                        .last("LIMIT " + SUGGEST_PER_TYPE));
        // 2.混合去重（LinkedHashSet 保序），最多 6 条
        Set<String> merged = new LinkedHashSet<>();
        attractions.forEach(a -> merged.add(a.getName()));
        notes.forEach(n -> merged.add(n.getTitle()));
        users.forEach(u -> merged.add(u.getNickName()));
        List<String> result = new ArrayList<>(merged);
        if (result.size() > SUGGEST_MAX) {
            result = result.subList(0, SUGGEST_MAX);
        }
        return Result.ok(result);
    }

    /**
     * 【Elasticsearch搜索的实现】把关键字转成 ES JSON 字符串字面量（转义双引号与反斜杠，防注入/防 JSON 破坏）
     * @param keyword 搜索关键字
     * @return JSON 字符串字面量（含引号）
     */
    private String esString(String keyword) {
        return "\"" + keyword.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /**
     * 【全局搜索的实现】批量查询用户昵称（按 userId 列表一次查齐，避免 N+1）
     * @param userIds 游记作者 userId 列表
     * @return userId → nickName 映射（用户不存在时不含该 key，调用方兜底"匿名"）
     */
    private Map<Long, String> queryNickNames(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> distinctIds = userIds.stream().distinct().collect(Collectors.toList());
        return userMapper.selectBatchIds(distinctIds).stream()
                .collect(Collectors.toMap(User::getId, User::getNickName, (a, b) -> a));
    }

    /**
     * 【全局搜索的实现】取 images 逗号分隔的第 1 张图片路径（与 UserServiceImpl.firstImage 同约定）
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
