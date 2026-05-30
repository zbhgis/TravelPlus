package com.travelplus.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelplus.entity.Attraction;
import com.travelplus.entity.TravelNote;
import com.travelplus.entity.User;
import com.travelplus.es.AttractionDoc;
import com.travelplus.es.NoteDoc;
import com.travelplus.es.UserDoc;
import com.travelplus.mapper.AttractionMapper;
import com.travelplus.mapper.TravelNoteMapper;
import com.travelplus.mapper.UserMapper;
import com.travelplus.service.ISearchIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【Elasticsearch搜索的实现】搜索索引同步实现：MySQL → ES 全量/单条同步。
 * <p>
 * 实现细节（分页批量 + bulk 写入 + 异常处理）：
 * - 分页游标：每批 1000 条（BATCH_SIZE），用 MyBatis-Plus {@link Page} 分页查询，
 *   记录按主键正序保证分页稳定（selectPage 底层 LIMIT offset），当前页取完再取下一页直至空页。
 * - 批量写入：每批组装成文档 List 后一次 {@link ElasticsearchOperations#save}（内部走 ES bulk API，
 *   1000 条/请求，避免逐条 RTT；22 万条 ≈ 220 次 bulk）。
 * - 索引重建：全量前先删旧索引（delete 不存在索引时忽略异常）再按 @Document 注解 createWithMapping()
 *   重建映射，确保文档结构与实体字段始终一致。
 * - 游记作者昵称：分页内对 userId 去重后 selectBatchIds 一次查齐，冗余进 NoteDoc.name，
 *   避免同步过程 N+1 查询。
 * - 异常处理：ES 写入失败抛 RuntimeException 由调用方（启动初始化/手动接口）记录日志；
 *   /search 读取侧失败不影响系统可用（SearchServiceImpl 兜底 LIKE）。
 * </p>
 *
 * @author TravelPlus
 */
@Slf4j
@Service
public class SearchIndexServiceImpl implements ISearchIndexService {

    /** 【Elasticsearch搜索的实现】每批同步条数（分页批量 bulk 的批大小） */
    private static final int BATCH_SIZE = 1000;

    @Resource
    private AttractionMapper attractionMapper;
    @Resource
    private TravelNoteMapper travelNoteMapper;
    @Resource
    private UserMapper userMapper;
    @Resource
    private ElasticsearchOperations operations;

    /**
     * 【Elasticsearch搜索的实现】全量重建三个搜索索引并同步 MySQL 全量数据。
     * <p>
     * 实现细节：
     * - 依次重建 attraction_es / note_es / user_es（先删后建保证映射最新）；
     * - 三张表各自分页批量同步（BATCH_SIZE=1000），返回各类同步条数；
     * - 任一阶段失败抛异常（日志记录），下次可重跑（幂等：先删后建）。
     * </p>
     * @return Map&lt;attraction|note|user, 同步条数&gt;
     */
    @Override
    public Map<String, Long> fullRebuild() {
        log.info("【Elasticsearch搜索的实现】开始全量重建 ES 索引...");
        long attractionCount = rebuildAttractions();
        long noteCount = rebuildNotes();
        long userCount = rebuildUsers();
        Map<String, Long> result = new LinkedHashMap<>(3);
        result.put("attraction", attractionCount);
        result.put("note", noteCount);
        result.put("user", userCount);
        log.info("【Elasticsearch搜索的实现】全量重建完成：attraction_es={}，note_es={}，user_es={}",
                attractionCount, noteCount, userCount);
        return result;
    }

    /**
     * 【Elasticsearch搜索的实现】重建并同步景点索引
     * @return 同步条数
     */
    private long rebuildAttractions() {
        recreateIndex(AttractionDoc.class);
        long total = 0;
        long pageNo = 1;
        while (true) {
            Page<Attraction> page = attractionMapper.selectPage(new Page<>(pageNo, BATCH_SIZE),
                    new LambdaQueryWrapper<Attraction>().orderByAsc(Attraction::getId));
            List<Attraction> records = page.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }
            List<AttractionDoc> docs = records.stream().map(a -> {
                AttractionDoc doc = new AttractionDoc();
                doc.setId(a.getId());
                doc.setName(a.getName());
                doc.setImage(firstImage(a.getImages()));
                doc.setScore(a.getScore() == null ? null : a.getScore().longValue());
                doc.setAvgPrice(a.getAvgPrice());
                doc.setDestination(a.getDestination());
                return doc;
            }).collect(Collectors.toList());
            operations.save(docs, IndexCoordinates.of("attraction_es"));
            total += docs.size();
            log.debug("【Elasticsearch搜索的实现】attraction_es 已同步 {} 条（累计 {}）", docs.size(), total);
            pageNo++;
            if (docs.size() < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    /**
     * 【Elasticsearch搜索的实现】重建并同步游记索引（作者昵称批量查询冗余）
     * @return 同步条数
     */
    private long rebuildNotes() {
        recreateIndex(NoteDoc.class);
        long total = 0;
        long pageNo = 1;
        while (true) {
            Page<TravelNote> page = travelNoteMapper.selectPage(new Page<>(pageNo, BATCH_SIZE),
                    new LambdaQueryWrapper<TravelNote>().orderByAsc(TravelNote::getId));
            List<TravelNote> records = page.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }
            // 本批作者昵称去重后一次查齐（避免逐条查询 N+1）
            List<Long> userIds = records.stream().map(TravelNote::getUserId).distinct().collect(Collectors.toList());
            Map<Long, String> nickNameMap = queryNickNames(userIds);
            List<NoteDoc> docs = records.stream().map(n -> {
                NoteDoc doc = new NoteDoc();
                doc.setId(n.getId());
                doc.setTitle(n.getTitle());
                doc.setImage(firstImage(n.getImages()));
                doc.setUserId(n.getUserId());
                doc.setName(nickNameMap.getOrDefault(n.getUserId(), "匿名"));
                doc.setVisible(n.getVisible());
                return doc;
            }).collect(Collectors.toList());
            operations.save(docs, IndexCoordinates.of("note_es"));
            total += docs.size();
            log.debug("【Elasticsearch搜索的实现】note_es 已同步 {} 条（累计 {}）", docs.size(), total);
            pageNo++;
            if (docs.size() < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    /**
     * 【Elasticsearch搜索的实现】重建并同步用户索引
     * @return 同步条数
     */
    private long rebuildUsers() {
        recreateIndex(UserDoc.class);
        long total = 0;
        long pageNo = 1;
        while (true) {
            Page<User> page = userMapper.selectPage(new Page<>(pageNo, BATCH_SIZE),
                    new LambdaQueryWrapper<User>().orderByAsc(User::getId));
            List<User> records = page.getRecords();
            if (records == null || records.isEmpty()) {
                break;
            }
            List<UserDoc> docs = records.stream().map(u -> {
                UserDoc doc = new UserDoc();
                doc.setId(u.getId());
                doc.setNickName(u.getNickName());
                doc.setIcon(u.getIcon());
                return doc;
            }).collect(Collectors.toList());
            operations.save(docs, IndexCoordinates.of("user_es"));
            total += docs.size();
            log.debug("【Elasticsearch搜索的实现】user_es 已同步 {} 条（累计 {}）", docs.size(), total);
            pageNo++;
            if (docs.size() < BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    /**
     * 【Elasticsearch搜索的实现】删除旧索引并按 @Document 注解重建映射（保证结构一致）。
     * 索引不存在时 delete 抛异常，直接忽略后走 createWithMapping。
     * @param docClass 文档类（indexName 从 @Document 取）
     */
    private void recreateIndex(Class<?> docClass) {
        IndexOperations indexOps = operations.indexOps(docClass);
        try {
            if (indexOps.exists()) {
                indexOps.delete();
            }
        } catch (Exception e) {
            log.warn("【Elasticsearch搜索的实现】删除索引失败（忽略继续重建）：{}", e.getMessage());
        }
        indexOps.createWithMapping();
        log.debug("【Elasticsearch搜索的实现】索引 {} 重建完成", indexOps.getIndexCoordinates().getIndexName());
    }

    /**
     * 【Elasticsearch搜索的实现】批量查询用户昵称（去重一次查齐）
     * @param userIds 用户 id 列表
     * @return userId → nickName 映射（缺省空 Map，调用方兜底"匿名"）
     */
    private Map<Long, String> queryNickNames(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return userMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, User::getNickName, (a, b) -> a));
    }

    /**
     * 【Elasticsearch搜索的实现】取 images 逗号分隔第 1 张图片路径（与 SearchServiceImpl.firstImage 同约定）
     * @param images 逗号分隔图片路径
     * @return 首图；为空返回 null
     */
    private String firstImage(String images) {
        if (StrUtil.isBlank(images)) {
            return null;
        }
        String[] parts = images.split(",");
        return parts.length > 0 ? parts[0] : null;
    }

    /**
     * 【Elasticsearch搜索的实现】单条同步景点到 ES（upsert：id 相同覆盖）
     * @param id 景点 id
     */
    @Override
    public void indexAttraction(Long id) {
        Attraction a = attractionMapper.selectById(id);
        if (a == null) {
            return;
        }
        AttractionDoc doc = new AttractionDoc();
        doc.setId(a.getId());
        doc.setName(a.getName());
        doc.setImage(firstImage(a.getImages()));
        doc.setScore(a.getScore() == null ? null : a.getScore().longValue());
        doc.setAvgPrice(a.getAvgPrice());
        doc.setDestination(a.getDestination());
        operations.save(doc, IndexCoordinates.of("attraction_es"));
    }

    /**
     * 【Elasticsearch搜索的实现】单条同步游记到 ES（upsert）
     * @param id 游记 id
     */
    @Override
    public void indexNote(Long id) {
        TravelNote n = travelNoteMapper.selectById(id);
        if (n == null) {
            return;
        }
        NoteDoc doc = new NoteDoc();
        doc.setId(n.getId());
        doc.setTitle(n.getTitle());
        doc.setImage(firstImage(n.getImages()));
        doc.setUserId(n.getUserId());
        doc.setName(queryNickNames(new ArrayList<>(List.of(n.getUserId()))).getOrDefault(n.getUserId(), "匿名"));
        doc.setVisible(n.getVisible());
        operations.save(doc, IndexCoordinates.of("note_es"));
    }

    /**
     * 【Elasticsearch搜索的实现】单条同步用户到 ES（upsert）
     * @param id 用户 id
     */
    @Override
    public void indexUser(Long id) {
        User u = userMapper.selectById(id);
        if (u == null) {
            return;
        }
        UserDoc doc = new UserDoc();
        doc.setId(u.getId());
        doc.setNickName(u.getNickName());
        doc.setIcon(u.getIcon());
        operations.save(doc, IndexCoordinates.of("user_es"));
    }
}
