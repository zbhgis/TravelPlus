# seed-content-notes — 种子数据旅游主题改造报告（2026-08-06）

范围：4 条游记（tb_travel_note id=4,5,6,7）文案重写 + 14 个景点（tb_attraction id=1..14）图片内容替换。
图片源：Wikimedia Commons（Wikimedia API + 缩略图 URL，走 .NET TLS，curl 在此网络 TLS 握手失败不可用）。
执行结果：全部成功，失败项 0。

## A. 游记重写（4 条）

DB 更新：仅 UPDATE title/content/images；liked（4:13、5:2、6:11、7:11）、comments、create_time、user_id 均未改动。
正文均为 200~400 字中文小红书风格（分段/emoji/📍），CHAR_LENGTH 实测 204 / 218 / 216 / 239。

| id | 新标题 | 正文主题 | 字数 | 新图片（images 字段） |
|----|--------|----------|------|------------------------|
| 4 | 西湖断桥｜白堤漫步半日游，杭州的浪漫都藏在这里🌅 | 断桥→白堤→平湖秋月徒步 + 西湖日落 | 204 | /imgs/blogs/4/0.jpg,/imgs/blogs/4/1.jpg,/imgs/blogs/4/2.jpg |
| 5 | 杭州灵隐寺｜飞来峰下许个愿，把好运带回家🙏 | 飞来峰石窟→灵隐寺→永福寺祈福 | 218 | /imgs/blogs/5/0.jpg,/imgs/blogs/5/1.jpg,/imgs/blogs/5/2.jpg |
| 6 | 杭州西溪湿地｜摇橹船穿过芦苇荡，周末就该这样慢下来🛶 | 摇橹船→芦苇荡→虾龙滩观鸟 | 216 | /imgs/blogs/6/0.jpg,/imgs/blogs/6/1.jpg,/imgs/blogs/6/2.jpg |
| 7 | 杭州龙井村｜九溪烟树茶园徒步，把春天泡进一杯茶里🍵 | 九溪烟树→九溪十八涧→龙井村问茶 | 239 | /imgs/blogs/7/0.jpg,/imgs/blogs/7/1.jpg,/imgs/blogs/7/2.jpg |

游记图片明细（新文件，均 >5KB 真实 jpg）：
- blogs/4/0.jpg ← Duanqiao.jpg（断桥实景）；blogs/4/1.jpg ← Huanglong & Broken Bridge - West Lake w Bai Causeway（白堤）；blogs/4/2.jpg ← West Lake (Hangzhou) sunset 2016 January（西湖日落）
- blogs/5/0.jpg ← Lingyin Temple in Hangzhou；blogs/5/1.jpg ← 灵隐寺石窟（飞来峰石刻）；blogs/5/2.jpg ← 永福寺 Yongfu Temple entrance name sign
- blogs/6/0.jpg ← That's our ride on the lake inside Xixi（西溪湖上摇橹船）；blogs/6/1.jpg ← Boats from the humped pedestrian bridge；blogs/6/2.jpg ← Xixi sprawling reserve（芦苇荡湿地）
- blogs/7/0.jpg ← Longjing Tea field, Meijiawu；blogs/7/1.jpg ← Longjing tea fields near Hangzhou；blogs/7/2.jpg ← Nine Greeks Meandering Through a Misty Forest（九溪烟树）

## B. 景点图片替换（14 个，共 39 张）

策略：保持原文件名与数量不变，仅覆盖文件内容 → DB images 字段零改动（已逐条校验 DB 路径 39/39 全部指向存在的文件）。
优先级：精确主题 → 近义主题；全部命中，无保留原图项。

| id | 景点 | 替换情况（源文件标题，Wikimedia Commons） |
|----|------|-------------------------------------------|
| 1 | 西湖风景名胜区 | 0.jpg=Hangzhou West Lake 7531；1.jpg=West Lake and Hangzhou Skyline |
| 2 | 断桥 | 0.jpg=Duanqiao.jpg；1.jpg=Broken Bridge area；2.jpg=West Lake 2025.06 02 |
| 3 | 苏堤 | 0.jpg=Su Causeway, West Lake；1.png=West Lake seen from Su Causeway（jpg→png 转换）；2.png=A view from Su causeway（jpg→png 转换） |
| 4 | 灵隐寺 | 0.jpg=Lingyin Temple in Hangzhou；1.jpg=Lingyin Temple 20161003 |
| 5 | 雷峰塔 | 0.jpg=Hangzhou Leifeng Pagoda 20161006；1.jpg=Leifeng Pagoda 20240729；2.jpg=Leifeng Pagoda 20191102 |
| 6 | 六和塔 | 0.jpg=Liuhe Pagoda, Hangzhou；1.jpg=Hangzhou Liuhe Pagoda 20161004；2.jpg=Liuhe Pagoda in Hangzhou 2015 |
| 7 | 西溪湿地 | 0.jpg=Xixi Wetland Park；1.jpg=That's our ride on the lake inside Xixi；2.jpg=Xixi sprawling reserve |
| 8 | 九溪烟树 | 0.jpg=Jiuxiyanshu（九溪烟树同名照）；1.jpg=Nine Greeks misty forest；2.jpg=Jiuxi 20161004 |
| 9 | 龙井村 | 0.jpg=Longjing Village；1.jpg=Longjing tea village Hangzhou；2.jpg=Longjing tea fields near Hangzhou |
| 10 | 宋城 | 0.jpg=Night in SongCheng；1.jpg=Song Dynasty City；2.jpg=Song Dynasty City |
| 11 | 清河坊 | 0.jpg=Hangzhou exotic bazaar（清河坊老街）；1.jpg=Hangzhou street 1；2.jpg=Hangzhou exotic bazaar (15) |
| 12 | 杭州动物园 | 0.jpg=Hangzhou Zoo 01；1.png=Elephant, Hangzhou zoo（jpg→png 转换） |
| 13 | 浙江省博物馆 | 0.jpg=Main Hall of Zhejiang Museum Gushan Branch（精确命中）；1.jpg=Liangzhu jade ornament（馆藏展品）；2.jpg=Zhejiang natural museum interior（近义：杭州博物馆内景） |
| 14 | 杭州乐园 | 0.jpg=杭州乐园大门；1.jpg=杭州乐园摩天轮；2.jpg=杭州乐园鸟瞰 |

## C. 验证

- DB 中文回查：mysql --batch --raw 直出文件逐字节核对，标题/正文/emoji/分段（真实换行 CHAR(10)）全部正确，无乱码；4 条字数 204/218/216/239 均在 200~400。
- 图片 HTTP 抽查：curl localhost:8081 抽查 13 个 URL（blogs 6 + attractions 7，含 jpg 与 png）全部 HTTP 200。
- 全部 51 张新图 >5KB 且扩展名与实际内容一致（magic bytes 逐文件校验：jpg=FFD8、png=89504E47，bad=0；3 处 png 槽位用 System.Drawing 将 jpg 重新编码为真实 png）。
- 景点 DB 路径完整性：tb_attraction 39 个 images 路径 39/39 存在（DB 零改动）。

## 失败项 / 保留原图项

- 失败项：0（51/51 下载成功）
- 保留原图项：无（14 个景点全部命中 Wikimedia 精确或近义主题）

## 备注（坑）

- 本机 curl.exe 对 commons.wikimedia.org 报 SSL connect error（exit 35，TCP 443 可连通但 TLS 握手被断）；改用 PowerShell Invoke-WebRequest / WebClient（.NET TLS）成功访问 Wikimedia API 与下载。
- Wikimedia API 突发 429：加 User-Agent 头 + 失败退避重试（2s/4s/8s…）后稳定。
- 中文 SQL 进 MySQL：cmd /c 重定向 `<` 管道原字节最稳（PowerShell `Get-Content | docker exec` 管道会按 ANSI/GBK 重编码导致乱码与语法错误）；配合 --default-character-set=utf8mb4。
- 景点 id=3（1.png/2.png）与 id=12（1.png）为 png 槽位：Wikimedia 搜索返回 jpg 居多，采用下载 jpg → System.Drawing 转码真 png，保证扩展名=内容，DB 零改动。

---

# seed-content-notes — 第二轮：新增用户/游记/景点（2026-08-06）

范围：3 新用户 + 5 新游记 + 2 新景点（延续西湖旅游主题）。图片源 Wikimedia Commons（沿用 .NET TLS 下载法）。
执行结果：全部成功，失败项 0。

## A. 新增用户（tb_user + tb_user_info，3 个）

| id | 昵称 | 手机号 | icon | password | user_info |
|----|------|--------|------|----------|-----------|
| 1010 | 杭城小筑 | 13912345678 | ''（空） | '' | city=杭州，introduce=喜欢在西湖边散步的杭城小居民，fans12/followee8/gender1/credits66/level1 |
| 1011 | 西湖边的猫 | 13712345678 | '' | '' | city=杭州，introduce=一只爱晒太阳的猫，最爱西湖的黄昏，fans25/followee15/gender2/credits88/level2 |
| 1012 | 背包客阿杰 | 15812345678 | '' | '' | city=杭州，introduce=背着包走过很多城市，最爱还是杭州，fans40/followee20/gender1/credits120/level3 |

create_time/update_time 均 NOW()；手机号不与既有 13686869696/13838411438/13456789011/13456789001 冲突。

## B. 新增游记（tb_travel_note，5 篇，id=9..13）

liked/comments 为随机值（0~30 / 0~10），create_time 为近 1 年内随机过去时间。正文 200~400 字（实测 207/227/206/202/205）。

| id | user_id | 标题 | liked/comments | create_time | images |
|----|---------|------|----------------|-------------|--------|
| 9 | 1010 | 雷峰塔｜登塔俯瞰西湖全景，白蛇传里的千年传说🌇 | 15/1 | 2026-04-19 | /imgs/blogs/9/0.jpg,/imgs/blogs/9/1.jpg,/imgs/blogs/9/2.jpg |
| 10 | 1011 | 苏堤春晓｜六桥烟柳的春日漫游，杭州春天最浪漫的一条路🌸 | 30/1 | 2026-01-30 | /imgs/blogs/10/0.jpg,/imgs/blogs/10/1.jpg,/imgs/blogs/10/2.jpg |
| 11 | 1012 | 六和塔｜钱塘江畔听涛声，登塔远眺江天一色🌊 | 8/8 | 2026-07-07 | /imgs/blogs/11/0.jpg,/imgs/blogs/11/1.jpg,/imgs/blogs/11/2.jpg |
| 12 | 1010 | 清河坊老街｜南宋市井的烟火气，杭州最有人情味的一条街🏮 | 2/2 | 2025-11-30 | /imgs/blogs/12/0.jpg,/imgs/blogs/12/1.jpg,/imgs/blogs/12/2.jpg |
| 13 | 1011 | 满觉陇｜金秋桂花香满陇，杭州秋天最甜的一条村路🌼 | 26/1 | 2026-04-02 | /imgs/blogs/13/0.jpg,/imgs/blogs/13/1.jpg,/imgs/blogs/13/2.jpg |

游记图片明细（Wikimedia Commons 源文件标题）：
- blogs/9：0=Hangzhou Leifeng Pagoda 20161006；1=Leifeng Pagoda (20240729)；2=Leifeng Pagoda 20191102
- blogs/10：0=Sudi Spring（苏堤春晓）；1=蘇堤春曉（近代画报印刷件）；2=West Lake seen from Su Causeway
- blogs/11：0=Liuhe Pagoda, Hangzhou；1=Hangzhou Liuhe Pagoda 20161004；2=Liuhe Pagoda in Hangzhou 2015
- blogs/12：0=Hangzhou exotic bazaar（清河坊老街）；1=Hangzhou street 1；2=Hangzhou exotic bazaar (15)
- blogs/13：0=West Lake Lotus Root Starch with Osmanthus（桂花藕粉）；1=Osmanthus fragrans flowers；2=Osmanthus fragrans (orange flowers)

## C. 新增景点（tb_attraction，2 个，id=15..16）

| id | name | type_id | images | area/address | x,y | avg_price | sold/comments | score | open_hours |
|----|------|---------|--------|--------------|-----|-----------|---------------|-------|------------|
| 15 | 太子湾公园 | 7（公园） | /imgs/attraction/15/0.jpg,1.jpg,2.jpg | 西湖区/杭州市西湖区南山路6号 | 120.145,30.232 | 0 | 3200/1560 | 46（4.6） | 08:00-17:30 |
| 16 | 满觉陇 | 1（自然风光） | /imgs/attraction/16/0.jpg,1.jpg,2.jpg | 西湖区/杭州市西湖区满觉陇路75号 | 120.131,30.211 | 0 | 2100/980 | 45（4.5） | 全天开放 |

景点图片明细（Wikimedia Commons 源文件标题）：
- attraction/15：0=太子湾公园樱花与郁金香；1=杭州. 太子湾公园；2=杭州 太子湾
- attraction/16：0=Osmanthus fragrans ZH 11；1=Osmanthus fragrans Db100016；2=Guangxi Guilin dried osmanthus fragrans（桂花近义主题）

## D. 验证

- DB 回查：mysql --batch --raw 直出文件逐字节核对，用户昵称/游记标题正文/景点名全部中文正常，无乱码；icon/password 均为空串。
- 图片 HTTP：curl localhost:8081 抽查 10 个 URL（blogs 7 + attractions 3）全部 HTTP 200。
- 文件校验：21 张新图 >5KB 且扩展名=实际内容（magic bytes：jpg=FFD8，bad=0）。
- 既有数据未动：4 篇旧游记（id=4..7）、14 个旧景点、2 个旧用户及其图片文件均未改动。

## E. 失败项 / 近似替换记录

- 失败项：0（21/21 下载成功）。
- 近似替换：满觉陇专属照片 Wikimedia 无收录 → 用 Osmanthus（桂花）花/干花近义图；苏堤春晓无当代实景照，采用 Sudi Spring + 近代「蘇堤春曉」画报印刷件 + Su Causeway 湖景（主题相近）。
- 注：本轮共用 5 次 Wikimedia 搜索（Su Causeway spring / Manjuelong / osmanthus / Taiziwan / tulip），taiziwan 与 osmanthus 命中；Manjuelong 直接搜索为空，回退近义词。