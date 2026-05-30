# ES 搜索改造 — LIKE 基线（大数据量压测结果）

> 目的：接入 Elasticsearch 前，量化当前 MyBatis-Plus `LIKE '%kw%'` 模糊查询在**大数据量**下的性能基线，
> 作为后续 ES 对比的对照组。压测数据已落库保留（见 `scripts/perf/seed_*.sql`），可直接供 ES 同步。

## 一、测试环境

- 后端：Spring Boot 3.5.9 @ `http://localhost:8081`（`/search/**` 匿名放行，无需登录）
- MySQL：docker `travelplus-mysql`（端口 3307，root/123456，库 travelplus）
- 搜索接口：`GET /search?keyword=xxx`，内部 3 条 `LIKE '%kw%'` 模糊查询 + LIMIT 10：
  - `tb_attraction.name` → `tb_travel_note.title`（仅公开 visible=0）→ `tb_user.nick_name`
- 压测工具：`node scripts/perf/bench.js`（并发 N=20、持续 10s、预热 2s、keep-alive fetch）
- **数据量（2026-08-12 造数后，Seed SQL 见 `scripts/perf/`）**：

| 表 | 基线数据 | 压测造数 | 当前总量 | 命中设计 |
|---|---|---|---|---|
| tb_attraction | 26 | 100,000 | **100,026** | name 含「西湖/乌镇/千岛湖/灵隐寺」各 25%，如 `压测景点1西湖景区1` |
| tb_travel_note | 63 | 100,000 | **100,063** | title 全部含「杭州西湖游玩攻略」，如 `压测游记1杭州西湖游玩攻略` |
| tb_user | 48 | 20,000 | **20,048** | nick_name 全部含「小鱼」，如 `压测用户1小鱼`；phone 唯一（15300000001~） |

- **索引说明**：未为 LIKE 添加任何索引（`LIKE '%kw%'` 前导通配符本就走不了 B+ 树），保持公平基线。

## 二、LIKE 基线压测结果（2026-08-12）

| keyword | 命中特征 | QPS | avg(ms) | P50(ms) | P95(ms) | P99(ms) | min(ms) | max(ms) | errors | 请求数 |
|---|---|---|---|---|---|---|---|---|---|---|
| 西湖 | 高命中（景点 25% + 游记 100% 含） | **319.01** | **62.53** | **61.54** | **71.82** | **93.31** | 47.21 | 104.13 | 0 | 2553 |
| xyz不存在 | 零命中（最坏情况全表扫描） | **71.13** | **279.16** | **278.21** | **324.57** | **346.41** | 195.53 | 368.40 | 0 | 570 |

> 复跑命令：
> `node scripts/perf/bench.js --keywords "西湖,xyz不存在" --concurrency 20 --duration 10 --warmup 2`

## 三、结论与观察

1. **高命中词（西湖）**：LIMIT 10 可提前退出（匹配行分布在前 ~40 行即凑满 10 条），延迟主要来自 3 次串行 SQL + 作者昵称批量查询，avg ≈ 62ms、QPS ≈ 319。
2. **零命中词（xyz不存在）**：`LIKE '%kw%'` 对三张表共 22 万行**全表扫描且零匹配**，退无可退 —— avg ≈ 279ms、QPS ≈ 71，是 LIKE 方案的真实最坏基线。
3. 大数据量下 LIKE 退化显著：高命中与零命中的 P99 相差近 4 倍（93ms vs 346ms），且 QPS 上限仅 ~320。
4. **ES 改造后对比目标**：ES 全文检索两词均应为个位数毫秒级、QPS 上千，用本表数字作为对照基准。
5. 无错误（errors=0），后端连接池未打满，瓶颈在 MySQL LIKE 全扫描本身。

## 四、相关文件

- 造数脚本：`scripts/perf/seed_attraction.sql`、`scripts/perf/seed_travel_note.sql`、`scripts/perf/seed_user.sql`
- 压测脚本：`scripts/perf/bench.js`
- 执行方式：`docker exec -i travelplus-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 travelplus < seed_xxx.sql`
