# more-data-notes — 大规模丰富数据报告（2026-08-07）

范围：杭州西湖旅游主题大规模数据扩充——8 用户 + 12 游记 + 4 景点 + 18 关注 + 18 评论 + 2 秒杀票（含 Redis 库存），并同步种子脚本 travelplus.sql。
图片源：Wikimedia Commons（.NET TLS，curl 对 Wikimedia TLS 失败不可用）。执行结果：全部成功，失败项 0。

## A. 新增用户（tb_user + tb_user_info，8 个）

| id | 昵称 | 手机号 | user_info fans/followee | credits/gender |
|----|------|--------|--------------------------|----------------|
| 1013 | 西湖听雨人 | 13712340001 | 2/2 | 36/1 |
| 1014 | 断桥边喝咖啡 | 13812340002 | 2/1 | 52/0 |
| 1015 | 茶园小鹿 | 13912340003 | 2/2 | 78/1 |
| 1016 | 杭漂摄影狮 | 15012340004 | 2/1 | 91/1 |
| 1017 | 山野拾光 | 15112340005 | 2/2 | 24/0 |
| 1018 | 桂花巷姑娘 | 15212340006 | 2/1 | 65/1 |
| 1019 | 钱塘观潮客 | 15512340007 | 1/2 | 43/0 |
| 1020 | 良渚拾遗 | 15712340008 | 1/1 | 88/0 |

password 全部 = 123456 的 BCrypt `$2a$10$7cT568ZD3PrLJjx/Pw3iquSjf47buiJO6mXX0LH2RMY/B1tWap8/K`（与既有用户一致）；icon 空串 `''`；city='杭州'；level=0；手机号不冲突既有 7 个。fans/followee 按实际关注关系（新增 18 条）同步。

## B. 新增游记（tb_travel_note，12 篇，id=14..25）

| id | user_id | 标题 | 作者 | liked/comments | images |
|----|---------|------|------|----------------|--------|
| 14 | 1013 | 千岛湖｜碧水千岛间的慢时光🚤 | 新 | 32/5 | /imgs/blogs/14/{0,1,2}.jpg |
| 15 | 1014 | 乌镇西栅｜枕水江南的夜，一盏灯一条巷🏮 | 新 | 25/4 | /imgs/blogs/15/{0,1,2}.jpg |
| 16 | 1015 | 湘湖｜比西湖清静的宝藏湖泊，环湖骑行刚刚好🚴 | 新 | 12/1 | /imgs/blogs/16/{0,1,2}.jpg |
| 17 | 1016 | 良渚古城遗址｜五千年文明的晨光🌄 | 新 | 18/2 | /imgs/blogs/17/{0,1,2}.jpg |
| 18 | 1017 | 钱塘江夜游｜灯光秀与江风，杭州的另一种浪漫🌉 | 新 | 28/6 | /imgs/blogs/18/{0,1,2}.jpg |
| 19 | 1018 | 宋城千古情｜一生必看的一场演出🎭 | 新 | 35/8 | /imgs/blogs/19/{0,1,2}.jpg |
| 20 | 1019 | 浙江省博物馆｜孤山馆里的杭州千年📜 | 新 | 9/1 | /imgs/blogs/20/{0,1,2}.jpg |
| 21 | 1010 | 北高峰日出｜凌晨四点上山，等一场云海日出⛰️ | 老 | 40/9 | /imgs/blogs/21/{0,1,2}.jpg |
| 22 | 1011 | 法喜寺｜年轻人的第一炷香，求姻缘好灵🙏 | 老 | 22/7 | /imgs/blogs/22/{0,1,2}.jpg |
| 23 | 1012 | 太子湾春天｜郁金香与樱花的浪漫暴击🌸 | 老 | 30/5 | /imgs/blogs/23/{0,1,2}.jpg |
| 24 | 1020 | 杭州动物园｜遛娃好去处，一天看遍奇珍动物🦁 | 新 | 15/2 | /imgs/blogs/24/{0,1,2}.jpg |
| 25 | 1013 | 南宋御街｜穿越回临安城的烟火日常🏯 | 新 | 20/3 | /imgs/blogs/25/{0,1,2}.jpg |

正文全部 150~300 字（实测 205~262），小红书风格分段/emoji/📍。attraction_id 关联：14→17、15→18、16→19、17→20、19→5、20→11、23→15、24→10、25→9，其余 NULL。

游记图片明细（Wikimedia Commons 源文件标题）：
- blogs/14：0=Qiandao Lake (near Hangzhou)；1=Inside islands of Qiandao Lake；2=Qiandao Lake (near Hangzhou) 2
- blogs/15：0=Aerial panorama of Wuzhen；1=Aerial vistas of Wuzhen Water town；2=20090913 Wuzhen 5046
- blogs/16：0=Xiang Hu；1=Hushan Sq, Xianghu (1)；2=Hushan Sq, Xianghu (2)
- blogs/17：0..2=Archaeological Ruins of Liangzhu City（三张）
- blogs/18：0=Qiantangjiang River Bridge at night；1=Qiantang River Bridge；2=Qiantang River Bridge 2015
- blogs/19：0..2=Song Dynasty City（三张）
- blogs/20：0=Main Hall of Zhejiang Museum Gushan；1=Zhejiang Provincial Museum, West Lake；2=Zhejiang Provincial Museum 18
- blogs/21：0..2=杭州北高峰 panoramio（三张，北高峰实景）
- blogs/22：0=Faxi Temple Mahavira Hall；1=Upper Tianzhu Temple；2=20221214 Fahai Temple
- blogs/23：0..2=Taiziwan Park（02/03/05）
- blogs/24：0=Elephant, Hangzhou zoo；1=Hangzhou Zoo 01；2=Hangzhou Zoo 03
- blogs/25：0=Qinghefang DSC 6241；1=Post Office Branch at Qinghefang 01；2=Post Office Branch at Qinghefang 02

## C. 新增景点（tb_attraction，4 个，id=17..20）

| id | name | type_id | area/address | x,y | avg_price | sold/comments | score | open_hours |
|----|------|---------|--------------|-----|-----------|---------------|-------|------------|
| 17 | 千岛湖风景区 | 6 湖泊山水 | 淳安县/杭州市淳安县千岛湖镇梦姑路 | 119.04727,29.60438 | 150 | 9800/3560 | 47 | 08:00-17:30 |
| 18 | 乌镇景区 | 8 文化街区 | 桐乡市/嘉兴市桐乡市乌镇镇 | 120.48807,30.74142 | 150 | 11500/4200 | 48 | 09:00-22:00 |
| 19 | 湘湖旅游度假区 | 6 湖泊山水 | 萧山区/杭州市萧山区湘湖路 | 120.22457,30.15919 | 0 | 5200/1860 | 44 | 全天开放 |
| 20 | 良渚古城遗址公园 | 2 人文古迹 | 余杭区/杭州市余杭区凤都路与104国道交叉口 | 119.97658,30.39779 | 60 | 6100/2380 | 49 | 09:00-17:00 |

景点图片（Wikimedia）：
- attraction/17：0=Mt Banner and Thousand Island Lake；1=Qiandao Lake (near Hangzhou) 3；2=Thousand Island Lake (14779665597)
- attraction/18：0..2=Canal in Wuzhen（01/03/04）
- attraction/19：0=Farm near Xianghu；1=Farm near Xianghu (2)；2=Xiang Hu
- attraction/20：0..2=Archaeological Ruins of Liangzhu City（另三张，与游记17不重复）

## D. 关注关系（tb_follow，新增 18 条，id=14..31，总数 24）

- 新用户互关：1013↔1014、1015↔1016、1017↔1018、1019↔1020
- 新用户关注老用户：1013→1010、1015→1012、1017→1011、1019→1010
- 老用户回关：1010→1013/1015/1017、1011→1014/1016、1012→1018
- 与既有 6 条（1→1011/2/1010、1011→1、1012→1、1010→2）零重复；INSERT IGNORE + 唯一约束 uk_user_follow 兜底。

## E. 评论（tb_travel_note_comment，18 条，id=3..20）

分散到游记 4-13：4(3条)、5(2)、6(2)、7(2)、9(2)、10(2)、11(2)、12(1)、13(2)，新老用户混合。内容 10~40 字短评。**已同步游记 comments 计数**：4:104→107、5:0→2、6:0→2、7:0→2、9:1→3、10:1→3、11:8→10、12:2→3、13:1→3。

## F. 秒杀票（tb_ticket 21/22 + tb_seckill_ticket + Redis）

- tb_ticket：21 千岛湖游船秒杀票（attraction 17，pay 9900/actual 15000 分）、22 乌镇西栅日游秒杀票（attraction 18，pay 12900/actual 19000 分），type=1，status=1
- tb_seckill_ticket：ticket 21 stock=50、ticket 22 stock=30，begin_time=2026-08-07 00:00:00，end_time=2026-08-14 00:00:00
- Redis 库存：`seckill:stock:21 = 50`、`seckill:stock:22 = 30`（key 与 seckill.lua 一致）

## G. 种子脚本同步

`src/main/resources/db/travelplus.sql` 已同步（UTF-8 无 BOM）：
- 追加：tb_user 8 条、tb_user_info 8 条、tb_travel_note 12 条、tb_attraction 4 条、tb_follow 18 条、tb_travel_note_comment 18 条、tb_ticket 2 条、tb_seckill_ticket 2 条
- 更新：tb_travel_note 既有 4-13 的 comments 计数与 update_time；AUTO_INCREMENT 对齐运行库（tb_user=1021、tb_travel_note=26、tb_travel_note_comment=21、tb_attraction=21、tb_ticket=23、tb_follow=32）
- tb_follow 用普通 INSERT（数据唯一），唯一约束保留在表结构

## H. 验证

- 运行库行数：用户 15、user_info 11、游记 21、景点 20、类型 10、关注 24、评论 18、门票 4、秒杀 3 —— 全部符合预期。
- 图片：48 张新图全部 >5KB 且 magic bytes=FFD8；HTTP 抽查 18 个 URL（blogs 14 + attractions 4）全部 200。
- 中文核对：游记标题/正文/评论 HEX 逐字节验证（如 14 号标题 HEX=E58D83...），emoji 正确，无乱码（PowerShell 重定向显示 �? 为终端编码问题，非 DB 数据问题）。
- 临时库 seed_verify：CREATE → 导入 travelplus.sql → 行数与运行库一致（15/11/21/20/10/24/18/4/3/0）→ 已 DROP。tb_follow uk_user_follow 唯一约束存在。

## 失败项 / 近似替换记录

- 失败项：0（48/48 下载成功）。
- 近似替换：北高峰（beigao）无英文命名照片 → 用 panoramio「杭州北高峰」系列；法喜寺搜索英文"Fahai Temple Hangzhou"空 → 中文「法喜寺 杭州」命中 Faxi Temple + 上天竺寺 + Fahai Temple 实景；湘湖无大湖实景 → 用 Xiang Hu + Hushan Sq 湖面近景 + Farm near Xianghu。

## 备注（坑）

- Wikimedia 对高频无 User-Agent 请求触发 robot policy（`Please honor our robot policy`）→ 必须带浏览器/具名 UA 且请求间隔 ~0.8-1s，失败退避 3s/6s/12s。
- 精确 titles API 对中文文件名（如 `File:杭州.北高峰 - panoramio.jpg`）URL 编码会失败（-1 未命中）→ 改用 generator=search + 标题通配符匹配取 thumburl。
- 批量 INSERT 中途撞唯一键（首次把 user_info 写了两份）→ 单条 SQL 含 `tb_user` 与 `tb_user_info` 时误以为重复，实际是 01_users.sql 里误留了 user_info 块导致二次执行冲突；拆分文件+分步执行后解决。教训：每表独立 .sql 文件、先查 MAX(id) 再定 id。
- 中文 SQL 进 MySQL 沿用 `cmd /c docker exec ... < file.sql` 管道 + `--default-character-set=utf8mb4`，文件 UTF-8 无 BOM。