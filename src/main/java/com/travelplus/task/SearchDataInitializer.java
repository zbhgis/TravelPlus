package com.travelplus.task;

import com.travelplus.es.AttractionDoc;
import com.travelplus.es.NoteDoc;
import com.travelplus.es.UserDoc;
import com.travelplus.service.ISearchIndexService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.mapping.IndexCoordinates;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 【Elasticsearch搜索的实现】启动全量同步初始化器：应用启动后检查 ES 索引数据，缺失则触发全量重建。
 * <p>
 * 实现细节：
 * - 用 {@link ApplicationRunner} 在 Spring 容器就绪后执行（晚于依赖注入，ES 客户端已可用）。
 * - 开关：travelplus.es.enabled=false 时跳过；travelplus.es.sync-force=true 时强制全量重建。
 * - 判定条件：三个索引任意一个不存在或文档数为 0 时触发 fullRebuild（幂等，重复启动不会重复同步）。
 * - 不阻塞关键路径：同步 22 万条（约 220 次 bulk）耗时数十秒，放在独立线程异步执行，
 *   应用先完成启动对外服务，索引在后台补齐；同步期间 /search 仍可查（命中部分数据），
 *   同步完成后搜索即完整（生产环境一般用 CDC/定时任务，demo 用启动全量足够）。
 * - ES 不可达时 count 抛异常 → 捕获记录日志后跳过，不阻断启动（/search 有 LIKE 兜底）。
 * </p>
 *
 * @author TravelPlus
 */
@Slf4j
@Component
public class SearchDataInitializer implements ApplicationRunner {

    @Resource
    private ISearchIndexService searchIndexService;
    @Resource
    private ElasticsearchOperations operations;

    @Value("${travelplus.es.enabled:true}")
    private boolean esEnabled;
    /** 【Elasticsearch搜索的实现】启动时是否强制全量重建（默认 false：索引有数据即跳过） */
    @Value("${travelplus.es.sync-force:false}")
    private boolean syncForce;

    /**
     * 【Elasticsearch搜索的实现】容器就绪回调：条件满足时异步触发全量同步
     * @param args 启动参数（未使用）
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!esEnabled) {
            log.info("【Elasticsearch搜索的实现】travelplus.es.enabled=false，跳过启动全量同步（/search 走 LIKE 降级）");
            return;
        }
        // 独立线程异步执行，避免阻塞启动（同步 22 万条耗时数十秒）
        Thread worker = new Thread(this::syncIfNeeded, "es-startup-sync");
        worker.setDaemon(false);
        worker.start();
    }

    /**
     * 【Elasticsearch搜索的实现】检查索引数据是否齐全，缺失则全量重建（异步线程入口）
     */
    private void syncIfNeeded() {
        try {
            if (!syncForce && indexHasData()) {
                log.info("【Elasticsearch搜索的实现】三个 ES 索引已有数据，跳过启动全量同步");
                return;
            }
            long start = System.currentTimeMillis();
            log.info("【Elasticsearch搜索的实现】检测到索引数据缺失（或配置强制重建），开始全量同步 MySQL→ES ...");
            searchIndexService.fullRebuild();
            log.info("【Elasticsearch搜索的实现】启动全量同步完成，耗时 {} ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            // ES 不可达 / 同步失败：记录日志即可，/search 有 LIKE 兜底保证可用
            log.error("【Elasticsearch搜索的实现】启动全量同步失败（/search 将走 LIKE 降级）：{}", e.getMessage(), e);
        }
    }

    /**
     * 【Elasticsearch搜索的实现】判断三个索引是否均已存在且文档数 &gt; 0。
     * 任一索引不存在或 count 抛异常（ES 不可达）都视为"数据缺失"。
     * @return true = 数据齐全无需同步
     */
    private boolean indexHasData() {
        try {
            return countOf(AttractionDoc.class, "attraction_es") > 0
                    && countOf(NoteDoc.class, "note_es") > 0
                    && countOf(UserDoc.class, "user_es") > 0;
        } catch (Exception e) {
            log.warn("【Elasticsearch搜索的实现】ES 索引探测异常（视为数据缺失，尝试全量同步）：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 【Elasticsearch搜索的实现】统计指定索引的文档数（索引不存在时抛异常由上层处理）
     * @param docClass 文档类（用于类型安全 count）
     * @param indexName 索引名
     * @return 文档数
     */
    private long countOf(Class<?> docClass, String indexName) {
        return operations.count(Query.findAll(), docClass, IndexCoordinates.of(indexName));
    }
}
