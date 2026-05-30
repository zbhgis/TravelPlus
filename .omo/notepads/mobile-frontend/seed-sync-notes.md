# Seed 同步记录：travelplus.sql 与运行库对齐（2026-08-06）

## 结论
`src/main/resources/db/travelplus.sql` 已按运行库（MySQL 8.0.46 容器 travelplus-mysql，库 travelplus）最终数据状态完成同步，并已通过临时库导入验证。新环境用该脚本初始化后与当前运行库一致（10 张表结构 + 数据，密码可登录，唯一约束存在）。

## 改动明细
### 表结构（CREATE TABLE）
- `tb_travel_note`：`attraction_id` 由 `NOT NULL` 改为 `NULL DEFAULT NULL`（游记 9-13 的 attraction_id 为 NULL，按运行库实际结构）；`AUTO_INCREMENT` 15。
- `tb_follow`：新增 `UNIQUE KEY uk_user_follow(user_id, follow_user_id)`（防止重复关注，与运行库 ALTER 一致）；`AUTO_INCREMENT` 14。
- 其余表结构与运行库一致；AUTO_INCREMENT 对齐运行库：tb_user=1015、tb_travel_note=15、tb_travel_note_comment=3、tb_attraction=17、tb_attraction_type=11、tb_ticket=11。

### 数据（INSERT，全部以运行库查询/mysqldump 提取为准）
- `tb_user`：7 条（1/2/4/5 原有 + 新增 1010 杭城小筑/1011 西湖边的猫/1012 背包客阿杰），密码均 BCrypt `$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K`（123456）。注意：运行库 7 个用户 update_time 均为 `2026-08-06 00:48:37`（批量改密痕迹），种子对原有 4 条保留旧时间、新 3 条用运行库时间。
- `tb_user_info`：**3 条**（1010/1011/1012，city=杭州）。⚠️ 任务预期 7 条，但实际运行库仅 3 条（1/2/4/5 无 info 记录），以运行库为准。
- `tb_travel_note`：9 篇。id 4-7 替换为运行库旅游主题新文案（断桥/灵隐寺/西溪湿地/龙井村），追加 9-13（雷峰塔/苏堤/六和塔/清河坊/满觉陇），图片 `/imgs/blogs/{id}/0..2.jpg`，liked/comments 与运行库一致。
- `tb_attraction`：16 条。1-14 改写为运行库最终状态（旅游主题名称 + 图片 `/imgs/attraction/{id}/...`，替换原美团外链图），追加 15 太子湾公园、16 满觉陇。
- `tb_attraction_type`：10 条。改写为运行库最终值（名称 自然风光/人文古迹…，icon `/imgs/types/*.jpg`，替换原 `/types/*.png`）。
- `tb_ticket`：2 条。1 西湖游船体验票 type=0；10 西湖游船秒杀票 type=1（pay_value=1, actual_value=100, status=1）。
- `tb_seckill_ticket`：1 条 ticket_id=10，**stock=10（初始库存）**，注释说明运行库已被秒杀下单消费扣减至 9。begin/end_time 沿用运行库。
- `tb_follow`：6 条（1→1011、1→2、1→1010、1011→1、1012→1、1010→2），作为初始社交演示数据。
- `tb_travel_note_comment`：空表不写数据；`tb_ticket_order`：不写订单（运行库 1 条秒杀订单 622874050099675137 为运行时业务数据），表内留注释说明。

### 移除
- 原"TravelPlus 场景化种子数据"UPDATE 块（景点/类型/门票场景化 UPDATE 语句）已删除，因为 INSERT 已是最终状态，UPDATE 冗余。

## 已知偏差（记录在案）
1. `tb_user_info` 运行库实为 3 条而非 7 条（任务预期不符，以运行库为准）。
2. 运行库 `tb_ticket` id=10 的 title/sub_title/rules 字段为**乱码**（HEX 确认系 UTF-8 双重编码损坏，如 `è¥¿æ¹–æ¸¸èˆ¹ç§’æ€ç¥¨`），种子按任务预期语义写入可读值：`西湖游船秒杀票 / 限时特惠 / 每人限购1张`。新环境正常，运行库该行需后续修复（不影响本任务）。
3. `tb_seckill_ticket.stock` 运行库=9（被消费），种子按约定写初始 10。

## 验证（真实执行）
1. 建临时库 `travelplus_seed_test`（utf8mb4）→ 导入修正后 travelplus.sql（当前服务器全局 sql_mode 已不含 NO_ZERO_DATE，直接导入成功）。
2. 断言通过：
   - 10 张表存在；行数 tb_user=7、tb_user_info=3、tb_travel_note=9、tb_attraction=16、tb_attraction_type=10、tb_ticket=2、tb_seckill_ticket=1、tb_ticket_order=0、tb_follow=6、tb_travel_note_comment=0。
   - `information_schema.statistics` 中 tb_follow 索引 `uk_user_follow` 存在（注意列名是 INDEX_NAME 而非 Key_name）。
   - 7 个用户 password 均以 `$2a$10$` 开头；中文昵称/标题导入无乱码。
   - tb_seckill_ticket(10).stock=10；tb_ticket(10) 标题/副标题/类型正确；tb_travel_note(9).attraction_id=NULL 正常。
3. 验证后已 `DROP DATABASE travelplus_seed_test`（信息架构确认已删除，无残留库）。
4. 文件为 UTF-8 无 BOM（首字节 `/*`，无 EF BB BF）。

## 环境信息
- 运行库：MySQL 8.0.46，容器 travelplus-mysql，端口 3307，库 travelplus（未做任何写操作，仅只读查询/mysqldump）。
- 图片为项目内 uploads/imgs 文件（DB 存 /imgs/... 路径），种子只存路径不含文件。
