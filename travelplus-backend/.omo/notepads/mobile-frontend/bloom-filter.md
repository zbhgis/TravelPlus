# 景点详情布隆过滤器防穿透（bloom-filter）

> 日期：2026-08-13；涉及：景点详情三道防线改造 —— 布隆过滤器（第一道）+ Redis 缓存（第二道）+ DB/空值占位（第三道）

## 一、背景与方案

- **痛点**：改造前 `queryById` 只靠 `cacheClient.queryWithPassThrough` 空值占位防穿透（第三道）。不存在 id 虽不直接打 DB，但每次都会写一条 `cache:attraction:{不存在id}=""`（TTL 2min）空值 key——恶意批量刷不存在的 id 会写大量垃圾 key 撑爆 Redis 内存。
- **方案**：在缓存之前加一道**布隆过滤器**前置拦截。三道防线：
  1. **第一道（前置拦截）**：请求到达先查布隆过滤器，`contains(id)=false`（确定不存在）→ 直接返回 fail"景点不存在！"，不查缓存、不查 DB、不写空值 key。
  2. **第二道（缓存查询）**：布隆判断"可能存在"→ 查 Redis 缓存（现有 `queryWithPassThrough` 缓存命中分支）。
  3. **第三道（DB + 空值兜底）**：缓存未命中 → 查 DB；DB 无数据写 2min 空值占位（**内部逻辑未改**）。

## 二、实现

- **常量**：`RedisConstants.BLOOM_ATTRACTION_KEY = "bloom:attraction:"`（Redisson RBloomFilter 数据直接落 Redis，key 为 String 类型）。
- **新服务**：`service/cache/AttractionBloomFilterService.java`（与 TicketListCacheService 同包）：
  - 注入 `RedissonClient` + `AttractionMapper`（只依赖 mapper，无循环依赖）。
  - `RBloomFilter<Object>`，`tryInit(EXPECTED_INSERTIONS=1000, FALSE_PROBABILITY=0.01)`。
  - **参数选择**：容量 1000（预留扩容，当前 26 条）；误判率 1%（越低位数组越大越占内存，防穿透只需挡"确定不存在"，1% 足够；误判只是多放行一次走缓存/DB 兜底，无害）。
  - **持久化/预热**：Redisson 布隆数据落 Redis，重启不丢。`tryInit` 只在 Redis 无该 key 时创建（返回 true），已存在则返回 false 保留原数据。`@PostConstruct` 中**仅当 freshInit=true 才全量预热**（`attractionMapper.selectList(null)` 取全部 id add），避免重复预热。
  - `contains(Long id)` / `add(Long id)`。
  - **只增不删**：布隆无法删元素（删位可能误删共享位的其他元素）；误判只能"不存在→可能存在"（安全侧），绝不会"存在→不存在"，因此拦截永远安全。
- **详情改造**：`AttractionServiceImpl.queryById` 方法开头（进 cacheClient 前）：
  ```java
  if (!bloomFilterService.contains(id)) {
      return Result.fail("景点不存在！");
  }
  ```
  布隆拦截后：contains=true → 走缓存/DB → 仍可能 null（1% 误判）→ 现有 `attraction == null` 兜底 fail，两层配合。
- **新增同步**：`AttractionController.saveAttraction` 在 `attractionService.save()` 回填自增 id 后调用 `bloomFilterService.add(id)`（controller 直接注入 bloom 服务，避免经由 service 产生环）。
- **未改动**：`CacheClient.queryWithPassThrough` 内部逻辑（第三道保持原样）；Redis 集群配置不动。

## 三、验证结果（重启后实测）

- 编译：`mvn compile -q` EXIT=0。启动日志：`AttractionBloomFilterService ... add 26 个景点 id`（预热正确）。
- 布隆 Redis 侧：`EXISTS bloom:attraction:` = 1，`MEMORY USAGE` = **2624 字节**（26 条仅 ~2.6KB，第一道防线内存占用极小）。
- **验证1 存在景点**：`GET /attraction/1`（西湖）→ 200 正常返回详情（布隆通过 → 缓存/DB 命中）。
- **验证2 布隆拦截**：`GET /attraction/999999` → fail"景点不存在！"；`SCAN cache:attraction:999999` **为空**（未写空值 key，证明第一道在缓存前生效）。
- **验证3 恶意批量**：连续 `GET /attraction/900000~900019`（20 个不存在 id）→ 20/20 全部快速拦截；keep-alive 连接下 **min=1ms / p50=1ms / avg≈3ms**（<5ms 达标，O(1) 位图判断，不碰 Redis 数据/DB）；期间 Redis `DBSIZE` 稳定 **4017** 无膨胀，`cache:attraction:9*` 零残留（未写任何空值占位）。
- **验证4 布隆预热正确**：接口行为推断——id=1 通过（返回详情）、id=999999 拦截（fail）；新 id 未被误拦（见验证5）。
- **验证5 新增同步**：`POST /attraction`（测试景点 name=布隆过滤测试景点）→ 返回 id=131097 → **立即** `GET /attraction/131097` → 200 正常返回（布隆已 add，不误拦）。测后清理：DB 删除该行（无 DELETE 接口，docker exec mysql 按 id 删）→ 缓存 key 删除 → 再 `GET /attraction/131097` 走布隆残留（只增不删）→ 放行到缓存/DB → DB null → fail"景点不存在！" + 写 2min 空值 key（**残留误判无害**，被第三道兜底）→ 该空值 key 已清理。
- 测试数据零残留：`tb_attraction` 无测试行；`cache:attraction:131097`、`cache:attraction:9*` 均无残留；DBSIZE 回到 4017。

## 四、注意事项（运维）

- **Redis 哨兵已发生 failover**：验证期间当前 master 为 **172.30.144.1:6381**（redis-slave 容器被提升，redis-master 容器降级为副本）。写命令（如 DEL）需打到当前 master——用 `SENTINEL get-master-addr-by-name mymaster` 查询，勿假定 6380 是 master。
- **布隆只增不删**：删除景点后其 id 在布隆中残留，后续查询会走缓存/DB 空值兜底返回 fail，无害；若删后立即被刷，会写一条 2min 空值 key（与改造前行为一致）。
- **布隆与缓存一致性**：新增景点必须走 `saveAttraction`（自动 add）；绕过接口直插 DB 的新景点会被布隆误拦（返回"景点不存在"），需手动 add 或重启预热。

