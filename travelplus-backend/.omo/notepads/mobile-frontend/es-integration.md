# ES 搜索集成 — 实施与验证记录（2026-08-12）

> 目标：把 `/search` 从 MyBatis-Plus LIKE 升级为 Elasticsearch 全文检索，保留 LIKE 降级兜底，接口结构零改动。
> 环境：docker `travelplus-es`（ES 8.17.4，http://localhost:9400，无认证）；Spring Boot 3.5.9 托管
> `spring-boot-starter-data-elasticsearch`（spring-boot-dependencies 中 elasticsearch-client 8.18.8，同 8.x 大版本可连 server 8.17）。

## 一、改动清单

| 文件 | 说明 |
|---|---|
| `pom.xml` | 新增 `spring-boot-starter-data-elasticsearch`（Boot 托管版本） |
| `application.yaml` | `spring.elasticsearch.uris: http://localhost:9400`、`connection-timeout: 2s`、`socket-timeout: 5s`（无认证）；`travelplus.es.enabled: true` 功能开关 |
| `es/AttractionDoc.java` | 索引 `attraction_es`：id/name(text+keyword)/image/score(long)/avgPrice(long)/destination |
| `es/NoteDoc.java` | 索引 `note_es`：id/title(text+keyword)/image/userId/name/visible（visible 用于可见性过滤） |
| `es/UserDoc.java` | 索引 `user_es`：id/nickName(text+keyword)/icon |
| `service/ISearchIndexService.java` | 同步接口：fullRebuild + 单条 indexAttraction/Note/User |
| `service/impl/SearchIndexServiceImpl.java` | 全量同步实现（删索引重建映射 + 每批 1000 分页 bulk + 作者昵称批量查冗余） |
| `task/SearchDataInitializer.java` | 启动初始化（ApplicationRunner，独立线程异步执行，索引有数据即跳过） |
| `controller/SearchController.java` | 新增 `POST /search/index/rebuild` 手动触发全量重建 |
| `service/impl/SearchServiceImpl.java` | `/search` 与 `/suggest` 改 ES 优先 + 异常降级 LIKE（返回结构逐字段不变） |

## 二、关键实现细节（踩坑记录）

1. **分词策略（不装 IK）**：实测 ES 8.17 `standard` 分词器对中文按"字"切分
   （`压测景点1西湖景区1` → `[压,测,景,点,1,西,湖,景,区,1]`），因此 `match_phrase` 短语查询可复刻
   SQL `LIKE '%kw%'` 的"连续子串命中"语义（西/湖 相邻成词组即命中），无需 IK 插件。
   中文按字匹配是接受取舍：单字搜索也能命中，但词组相关度不如 IK 的分词语义（demo 足够）。
2. **StringQuery 的 JSON 结构坑（必踩）**：Spring Data ES 5.x 的 `StringQuery` source 是 **query 部分**，
   不能带外层 `{"query":{...},"size":N}`，否则 ES 报 `parsing_exception: unknown query [query]`。
   条数控制用构造器 `new StringQuery(json, PageRequest.of(0, 10))`。
3. **Spring Data ES 5.5.7 API 差异**：
   - `IndexCoordinates` 在 `org.springframework.data.elasticsearch.core.mapping` 包（不是 core）；
   - `@Field` 没有 `fields` 子字段属性，keyword 子字段用 `@MultiField(mainField=@Field(Text), otherFields=@InnerField(suffix="keyword", type=Keyword))`；
   - 批量写入是 `ElasticsearchOperations.save(Iterable, IndexCoordinates)`（内部 bulk），不是 `saveAll`。
4. **可见性过滤**：note 查询用 `bool.must(match_phrase title) + filter(term visible=0 或 should visible=0/userId=当前用户)`，
   与 LIKE 实现的 `(visible=0 OR user_id=当前用户)` 语义一致；游客只搜公开笔记。
5. **降级策略**：`search()`/`suggest()` 先走 ES，任意异常 catch 后落 LIKE 实现（日志 WARN 记录）；
   `travelplus.es.enabled=false` 时完全不触 ES。ES 挂了搜索仍可用，只是慢。
6. **启动同步不阻塞启动**：`SearchDataInitializer` 用独立线程 `es-startup-sync` 异步执行，
   22 万条耗时约 60s，应用先完成启动对外服务（生产一般用 CDC/定时任务，demo 用启动全量 + 手动接口足够）。

## 三、验证结果（全部通过）

| 验证项 | 结果 |
|---|---|
| `mvn compile -q` | EXIT=0 |
| 启动日志 ES 全量同步 | attraction_es=100026、note_es=100063、user_es=20048，耗时 61597 ms |
| `_cat/indices` 文档数 | 100026 / 100063 / 20048 ✓ |
| `GET /search?keyword=西湖` | success=true，attractions=10、notes=10、users=2，结构 `{id,name,image,score,avgPrice,destination}` / `{id,title,image,userId,name}` / `{id,nickName,icon}` ✓ |
| `GET /search?keyword=压测游记` | notes=10 命中 ✓ |
| `GET /search?keyword=压测用户` | users=10 命中 ✓ |
| `GET /search?keyword=xyz不存在` | 三类全空（无异常） ✓ |
| `GET /search/hot` | 返回固定 10 个热词 ✓ |
| `GET /search/suggest?keyword=西` | 6 条联想词 ✓ |
| `POST /search/index/rebuild` | 返回 `{attraction:100026,note:100063,user:20048}`（幂等重建） ✓ |
| LIKE 降级 | 已实测：ES 查询报错时（StringQuery bug 阶段）自动降级 LIKE 返回正常结果，日志出现 WARN ✓ |
| 性能 | ES 检索 ~30-55ms/请求（vs LIKE 基线高命中 62ms / 零命中 279ms，见 es-baseline.md） |

## 四、备注

- 未删 10 万测试数据（供 ES/LIKE 对比压测）；未装 IK（标准分词 + keyword 子字段够用）。
- `/search/**` 保持 MvcConfig 匿名放行；`/search/hot` 常量与返回结构未改动，前端零改动。
- 后续增量同步扩展点：`SearchIndexServiceImpl.indexAttraction/Note/User(id)` 单条 upsert，
  业务写库后调用即可（本次未接入写链路）。