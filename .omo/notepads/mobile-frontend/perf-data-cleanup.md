# 压测污染数据清理摘要

日期：2026-08-12
范围：TravelPlus 后端（MySQL docker travelplus-mysql 3307 root/123456，库 travelplus）ES 性能对比压测（scripts/perf/seed_*.sql）产生的数据膨胀清理，恢复服务响应性能。

## 背景（根因）

- 压测为 ES 性能对比追加了 10 万级数据：	b_attraction 26 → 100,026、	b_travel_note 63 → 100,063、	b_user 48 → 20,048。
- 前端"旅行"页（#/travel）卡死根因：GET /map/points 执行 ttractionMapper.selectList(null) 全量查询 → 返回 13.5MB / 10 万条坐标 → 	ravel.js 的 points.forEach 打 10 万 Leaflet marker → 浏览器卡死。
- 其余接口（景点列表/搜索等）同样因数据膨胀变慢。

## 清理内容（MySQL）

压测数据特征（seed 脚本命名规则）：
- 景点：压测景点{序号}…景区{序号}，id 从 27 开始。
- 游记：压测游记{序号}杭州西湖游玩攻略，id 从 70 开始（原始游记最大 id=69，原始 id 4~69 缺 8/26/27，共 63 条）。
- 用户：压测用户{序号}小鱼，phone 153…，id 从 2084 开始（原始用户 id 1/2/4/5 + 1010~1053 共 48 条，含客服 1021；id 1054~2083 区间无用户）。

执行（事务包裹，临时 SQL UTF-8 无 BOM，Get-Content -Raw | docker exec -i travelplus-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 travelplus 管道执行）：
- DELETE FROM tb_attraction WHERE id > 26; → 删 100,000 条
- DELETE FROM tb_travel_note WHERE id > 69; → 删 100,000 条
- DELETE FROM tb_user WHERE id > 2083; → 删 20,000 条

删除后计数：	b_attraction=26、	b_travel_note=63、	b_user=48（与 ES 集成前一致）。

## 级联检查结论

- **压测用户（id>2083）零业务引用**：tb_ticket_order / tb_favorite / tb_travel_note / tb_chat_message(from/to) / tb_travel_note_comment / tb_follow / tb_trip / tb_attraction_comment / tb_attraction_rating / tb_notify / tb_user_info / tb_attraction_checkin / tb_points_record / tb_balance_record / tb_chat_hidden / tb_chat_pin 全部 0 条 → 直接删用户安全。
- **压测景点（id>26）零引用**：tb_favorite(attraction) / tb_attraction_comment / tb_attraction_rating / tb_attraction_checkin / tb_ticket / tb_travel_note(attraction_id) / tb_trip_day_item 全部 0 条。
- **压测游记（id>69）零引用**：tb_travel_note_comment / tb_favorite(note) 全部 0 条。
- 注意：压测游记的 user_id 循环真实用户 1/2/4/5/1010（seed 脚本故意复用现有用户），删游记不影响用户。

## Redis 清理结论

- 扫描 rowse:*：仅 3 个 key（browse:1 / browse:2 / browse:1010），全部为真实用户 → 保留。
- 
ote:liked:* 63 个 key：id 全部落在原始游记区间 4~69 → 保留。
- ttraction:liked:* 2 个 key：id 13/24 均原始景点 → 保留。
- 其余 key（follows/feed/sign/icr:order/seckill:stock|order/cache:attraction）均为真实业务 → 无需清理。
- **结论：压测未产生 Redis 残留，无需删除任何 key。**

## ES 索引重建

- 清理后 ES 三索引仍为 10 万级文档（user_es 20048 / note_es 100063 / attraction_es 100026）。
- 调用 POST /search/index/rebuild（SearchController.rebuildIndex → SearchIndexServiceImpl.fullRebuild，删旧索引+分页批量重灌）：
  {"success":true,"data":{"attraction":26,"note":63,"user":48}}
- 验证 GET :9400/_cat/indices/attraction_es,note_es,user_es → 文档数 26/63/48，与 DB 一致。

## 验证结果（接口）

- GET /map/points → 200，**3094 B / 91ms**（原 13.5MB / 秒级），返回 26 条坐标。
- GET /trip/list?current=1（带 token）→ 200，94ms，data=[]。
- GET /attraction/destinations → 200（杭州/厦门）。
- GET /search?keyword=西湖 → 200（ES 26/63/48 文档命中）。
- GET /attraction/list?current=1、GET /attraction/1、GET /note/hot → 200 正常。
- GET /ticket/list/{1,17,18,21} → 200（门票+秒杀券）。
- GET /user/wallet → 200（余额/积分/秒杀券数正常）、GET /user/browse/list → 200。

## 验证结果（浏览器 Playwright 375×812）

- 登录 13912345678/123456 后切 #/travel：地图 26 个 Marker、附近景点卡片（断桥 0.4km 等 10 个）、旅行概览三卡（打卡/城市/计划）、我的旅行计划空态 → **正常渲染，无卡死、无"加载中"卡住**。
- 切回 #/home（景点卡片正常）、#/messages（赞和收藏/新增关注 tab 正常）、#/attraction/1 详情（含"西湖游船秒杀票"）→ 全部正常。
- 网络请求确认 /map/points 200，响应仅 26 条坐标。

## 关键经验 / 加固建议

- **坑：** /map/points 是 selectList(null) 全量查询（MapController 注释注明"demo 规模 20 条可接受"）。压测/造数后此接口直接放大 5000 倍，前端逐点打 marker 必然卡死。**未来若再次压测造数，务必先清理或临时给该接口加 limit / 前端分批渲染。**
- 压测造数脚本保留在 scripts/perf/seed_*.sql（未删），如再使用前务必评估全量接口影响。
- token 请求头约定：uthorization（RefreshTokenInterceptor 原值读取，**不要加 Bearer 前缀**）。
- PowerShell 5.1 执行 SQL 用 Get-Content -Raw | docker exec -i ... mysql 管道（不支持 < 重定向）。