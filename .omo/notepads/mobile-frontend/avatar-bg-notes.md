# avatar-bg-notes — 用户头像 + 背景图 + 全库时间统一 2026 报告（2026-08-07）

范围：① 15 用户个性头像 + 小红书风格背景图；② 新注册用户默认头像；③ 全部表时间字段统一 2026 年。执行结果：全部成功，失败项 0。

## A. 用户头像（16 个，含默认）

- 数据源：DiceBear Notionists API `https://api.dicebear.com/9.x/notionists/png?seed={昵称}&size=256`（免费无版权、确定性输出）。已先测可用性：Invoke-WebRequest 200 + Content-Type image/png。
- seed 用昵称（user_* 随机昵称用户用昵称本身；default 用 "default"），风格统一 notionists 一种。
- 存 `uploads/imgs/user/avatar/{userId}.png`，16 个：1,2,4,5,1010~1020 + default.png，全部 >5KB、magic bytes=89504E47（真 PNG）。
- 目录 `uploads/imgs/user/avatar/` 与 `uploads/imgs/user/bg/` 已存在（首轮部分执行留下 16 个 PNG，本次按 seed 规范重新生成覆盖）。

## B. 用户背景图（15 张，杭州主题实景大图）

- 数据源：Wikimedia Commons（.NET TLS：Invoke-WebRequest/WebClient，curl 对 Wikimedia TLS 握手失败不可用，沿用既有经验）。
- 每用户不同主题：1 西湖/2 断桥/4 苏堤/5 灵隐寺/1010 龙井茶田/1011 西溪湿地/1012 雷峰塔/1013 钱塘江/1014 千岛湖/1015 乌镇/1016 湘湖/1017 良渚古城/1018 太子湾/1019 北高峰/1020 清河坊老街。
- 存 `uploads/imgs/user/bg/{userId}.jpg`，15 张全部 >5KB、magic bytes=FFD8（真 JPG）。
- 近似替换记录：1 西湖晨雾无精确实景 → 用 West Lake 7531 实景；1011 西溪湿地 → A bridge across a channel on the lake；1016 湘湖 → Hushan Sq, Xianghu；1018 太子湾 → Taiziwan Park 02；1019 北高峰 → 杭州. 北高峰 - panoramio。

## C. DB 更新（tb_user + 时间统一 2026）

- `tb_user` 15 条：`icon=/imgs/user/avatar/{id}.png`（覆盖旧路径 blog1.jpg/kkjtbcr.jpg/空），`bg_image=/imgs/user/bg/{id}.jpg`（原全空）。
- 时间统一 2026（SQL 文件管道法，UTF-8 无 BOM → `cmd /c "docker exec -i travelplus-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 travelplus < file.sql"`）：
  - `tb_travel_note`（21）：create_time/update_time 按 id 递增分布 2026-01-06 ~ 2026-08-07（非同一时刻）
  - `tb_user`（15）：create/update 2026-01-08 ~ 2026-05-22 按注册先后递增
  - `tb_travel_note_comment`（19 含运行库新增 id21）：2026-01-08 ~ 2026-07-02（紧随所属游记发布后）
  - `tb_follow`（23 运行库行，种子 24）：2026-02-15 ~ 2026-03-19
  - `tb_attraction`（20）/`tb_attraction_type`（10）/`tb_user_info`（12）/`tb_ticket`（4）：create/update 2026
  - `tb_seckill_ticket`：ticket 10 begin/end 调整为 **2026-08-01 00:00:00 ~ 2026-08-31 23:59:59**（当前有效，原 end 已过期）；ticket 21/22 保持 2026-08-07 00:00:00 ~ 2026-08-14 00:00:00（当前有效）
  - `tb_ticket_order`：create_time 2026-08-06（本就 2026，未动）
- 回查：全库 UNION 时间字段非 2026 计数 = 0；秒杀票 `begin<=NOW()<=end` 3/3。

## D. 注册默认头像（后端代码）

- `UserServiceImpl.register()`：`user.setIcon("")` → `user.setIcon("/imgs/user/avatar/default.png")`（注释【注册默认头像的实现】）。
- `UserServiceImpl.createUserWithPhone()`（验证码登录自动建号入口）：不再显式赋值，走实体默认值（注释说明）。
- `User.java` 实体：`private String icon = ""` → `= "/imgs/user/avatar/default.png"`（注释同步）。
- `mvn -q compile` EXIT=0 通过；未重启后端（orchestrator 统一处理，运行中的旧进程仍写空 icon）。

## E. 种子脚本同步（travelplus.sql，UTF-8 无 BOM）

- 15 用户 INSERT：icon（avatar 路径）+ bg_image（bg 路径）+ create/update 2026 已同步。
- 21 游记 / 19 评论 / 24 关注 / 20 景点 / 10 类型 / 11 user_info / 4 门票 / 3 秒杀票时间全部 2026 已同步；秒杀 ticket10 begin/end 调整同上。
- 验证：导入临时库 seed_verify_ab 行数 15/21/18/24/20/10/11/4/3 与预期一致、时间全部 2026、秒杀时段正确 → 已 DROP。

## F. 验证

- HTTP 抽查 10 个 URL（avatar 5 + bg 5）全部 200，Content-Type image/png|image/jpeg，len 全部 >5KB。
- 注册默认头像（运行中旧代码）：POST /user/code → redis 取码 → POST /user/login 自动建号成功（旧进程 icon 仍空串）；新代码编译已通过，重启后生效。测试用户 1021 已删除、Redis 验证码已清。
- AUTO_INCREMENT 说明：测试注册消耗 tb_user 计数至 1022（种子脚本仍 1021），数据行数不变，属无害计数器漂移。

## 备注（坑）

- DiceBear 可用（本机网络 OK，与 Wikimedia 不同），生成直接走 Invoke-WebRequest 即可。
- PowerShell 双引号字符串中反引号是转义符（`t = TAB）：写 `INSERT INTO `tb_...`` 时用 .NET WriteAllLines 构造 SQL 行会导致表名被吞（`tb_`→`\tb_`），必须改用单引号正则或直接 Edit。
- Wikimedia 高频请求带 User-Agent + 失败退避重试（3s/6s）；按标题通配符优先匹配，宽>=1000px 才收。