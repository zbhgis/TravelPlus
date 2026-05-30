# 景点价格一致性修复（列表"¥X 起" vs 详情门票价）

> 追加于 2026-08-12，后端修复 + 实测验证通过。

## 问题
列表页"¥X 起"与详情页门票价格不一致：列表读 `tb_attraction.avg_price`（静态预置，乌镇=150 分=1.5 元，过时冗余），详情门票列表读 `tb_ticket.pay_value`（乌镇=12900 分=129 元，真实票价）。两个数据源脱节。

## 修复口径（后端）
统一"¥X 起" = **该景点所有有效门票的最低 pay_value（分）**，无票=0（前端 `hasTicket && avgPrice>0 ? '¥'+fenToYuan+'起' : '免费'`，前端无需改）。

### A. 列表 `AttractionServiceImpl.attachListInfo`
- 原 `selectObjs(DISTINCT attraction_id)` 改为 `selectMaps`：`SELECT attraction_id, MIN(pay_value) AS minPrice FROM tb_ticket WHERE attraction_id IN (...) GROUP BY attraction_id`（一次取全，防 N+1），组装 `Map<attractionId, minPrice>`。
- 回填循环：`hasTicket = map.containsKey(id)`（集合语义与原来 DISTINCT 完全一致），`setAvgPrice(hasTicket ? minPrice : 0L)`。
- 有票景点 hasTicket=true + 最低票价；无票 0 → 前端"免费"。

### B. 详情 `queryById`
- 新增 `attachTicketPrice(attraction)`：`MIN(pay_value)` 单查该景点，覆盖 DB 静态 avg_price 列；无票置 0。与列表同源 → 列表/详情价格一致。

### C. 数据清理
- 一次性对齐：`UPDATE tb_attraction a SET a.avg_price = COALESCE((SELECT MIN(t.pay_value) FROM tb_ticket t WHERE t.attraction_id = a.id), 0)`（live DB 已执行，0 mismatch）。
- 同步种子 `travelplus.sql`：id=1 西湖 avg_price 0→1（票 4750 与 1 取最低）、id=17 千岛湖 150→9900、id=18 乌镇 150→12900；列注释改为"最低门票价（分），已对齐 tb_ticket MIN(pay_value)，无票=0"。
- `SearchServiceImpl` 直接读 DB avg_price 列 → 数据对齐后搜索结果也一致（未改代码）。

## 实测（后端重启后，端口 8081）
- `/attraction/of/type?typeId=8`：乌镇 id=18 avgPrice=12900 ✓
- `/attraction/of/type?typeId=6`：西湖 id=1 avgPrice=1 ✓、千岛湖 id=17 avgPrice=9900 ✓
- `/attraction/18` 详情：avgPrice=12900 ✓；`/ticket/list/18`：payValue=12900 ✓（一致）
- `/attraction/7` 免费景点：avgPrice=0 ✓
- `/attraction/of/destination?city=杭州`：id=1 avgPrice=1 ✓（同 attachListInfo 路径）
- `/search?keyword=乌镇`：avgPrice=12900 ✓
- 编译 `mvn compile -q` EXIT=0 ✓

## 踩坑 / 备注
- 金额单位保持"分"，前端 fenToYuan /100 展示，勿改单位。
- 详情 avgPrice 每次实时覆盖缓存实体的字段（不改 Redis），不依赖缓存失效，天然新鲜。
- 门票列表接口 `queryTicketOfAttraction` 仅返回 type=1 AND status=1 门票；最低价取"全部门票" MIN 与 hasTicket 语义一致（当前数据下各景点一致，若未来新增非 type=1 票且价格更低需复核口径）。