# 旅行强化功能（景点评论/动态评分/打卡足迹/城市推荐/游记关联景点）实现摘要
# mvn -q compile 通过（EXITCODE=0，仅 Maven 自身 sun.misc.Unsafe JDK24 告警）；未重启后端；未 git 操作；未改前端；未加新依赖
# Java LSP（jdtls）未安装，编译通过为权威验证（沿用先前任务约定）

## ① 表结构（DDL 已执行 + 种子同步）
- `tb_attraction_comment`：id/attraction_id/user_id/content(≤500)/create_time，KEY idx_attr(attraction_id,id) —— 一级平铺评论（无回复/置顶）
- `tb_attraction_rating`：id/attraction_id/user_id/score(TINYINT 1~5)/create_time，UNIQUE uk_ar(user_id,attraction_id) —— 一人一票可改分
- `tb_attraction_checkin`：id/attraction_id/user_id/create_time，UNIQUE uk_ac(user_id,attraction_id) —— 一人一景点一次（幂等）
- `tb_attraction` 新增列 `destination varchar(50) NOT NULL DEFAULT '杭州'`；存量 20 行自动回填 '杭州'（千岛湖/乌镇归杭州旅游圈）
- travelplus.sql 同步：destination 列（CREATE TABLE 末尾，与位置 INSERT 末值对齐）+ 3 表 DDL + 全部 seed

## ② 厦门景点数据（6 个，destination='厦门'，id 21~26）
- 21 鼓浪屿(type1 自然风光)/22 厦门大学(type2 人文古迹)/23 环岛路(type1)/24 曾厝垵(type8)/25 南普陀寺(type5)/26 沙坡尾(type8)
- 坐标厦门岛 118.079~118.153 / 24.436~24.45；score 40~47；open_hours/address/area 齐
- 每景点 2~3 张图下载到 uploads/imgs/attraction/{21..26}/0..2.jpg（共 17 张），images 路径已写入 DB 与种子
  - 注：Wikimedia upload.wikimedia.org 被机房 IP 429 限流，改走 Openverse API 索引的 CC 图（Flickr CDN 可下载），均为对应景点真实照片

## ③ 景点评论（【景点评论的实现】）
- `POST /attraction-comment`（需登录，/attraction-comment 被拦截）body {attractionId, content} → 校验景点存在/content 非空 → 插入；评论者 userId 服务端写
- `GET /attraction-comment/of/{attractionId}?current=`（需登录）→ create_time 倒序分页（MAX_PAGE_SIZE）+ 作者昵称/头像（userService 补 name/icon，用户不存在"匿名"兜底）
- 仿 TravelNoteComment 但**无回复/置顶**（简化：仅一级平铺）；评论数不维护 tb_attraction.comments，由评论表 COUNT（见详情）

## ④ 动态评分（【动态评分的实现】）
- `POST /attraction/rate`（body {attractionId, score}）1~5 校验；**唯一约束 upsert**——先查 uk_ar(user_id,attraction_id)，无则 INSERT，有则 UPDATE score（用户可改分）
- `GET /attraction/rate/status?attractionId=&userId=`（需登录；userId 可选缺省当前用户）→ data=1~5 或 null
- 鉴权注：POST /attraction/rate 与匿名 /attraction/* 匹配冲突，service 内 UserHolder 判空 fail"请先登录"（与 likeAttraction 双保险模式）
- `queryById` 增强：`avgScore = COALESCE(AVG(rating.score)*10, 原score)`（selectObjs 取 AVG，×10 保持与 score 同量纲、前端 /10 展示，无评分回退原 score）；+ ratingCount（COUNT 评分人数）+ myRating（登录用户自己的打分）；`comments` 字段被评论表 COUNT 实时覆盖（attachCommentCount）

## ⑤ 打卡与足迹（【打卡与足迹的实现】）
- `POST /attraction/checkin/{id}`（需登录）→ **唯一约束幂等**：先 COUNT(user_id,attraction_id)，已打卡直接 ok 不重复计数；未打卡 INSERT
- `GET /attraction/{id}` 增强：checkedIn（当前用户是否打卡，未登录 null）+ checkinCount（COUNT 打卡人数=来过统计）
- `GET /user/checkins/{id}?current=`（需登录；**足迹公开**，他人主页可看）→ 某用户打卡列表，按 create_time 倒序分页，join 景点返回 List<Map{id,name,image,x,y,checkinTime}>，景点已删除跳过，total=打卡总数

## ⑥ 城市推荐（【城市推荐景点的实现】）
- `GET /attraction/destinations`（公开）→ SELECT destination GROUP BY ORDER BY COUNT(*) DESC → List<String>（杭州20 / 厦门6）
- `GET /attraction/of/destination?city=&current=`（公开）→ destination 等值分页（MAX_PAGE_SIZE），data=List<Attraction>
- 均按 tb_attraction.destination 列筛选；无需 MvcConfig 改动（/attraction/* 与 /attraction/of/** 已匿名放行）

## ⑦ 游记关联景点（【游记关联景点的实现】）
- `TravelNoteServiceImpl.saveTravelNote`：attractionId **非必需**（null 不关联）；非 null 校验景点存在，不存在 fail"关联的景点不存在"
- `queryTravelNoteById`：note.attractionId 非空 → join tb_attraction 返回 attraction {id,name,image}（TravelNote 新增 @TableField(exist=false) attraction 字段），景点已删除置 null

## ⑧ 数据 seed（运行库已执行 + 种子同步）
- 评论 22 条：西湖(1)5、灵隐(2)4、鼓浪屿(21)4、南普陀(25)3、环岛路(23)2、厦大(22)2、曾厝垵(24)1、沙坡尾(26)1（新老用户混合；不强制同步 tb_attraction.comments，详情评论数=评论表 COUNT）
- 打分 28 条：西湖6、灵隐5、断桥4、鼓浪屿5、南普陀4、环岛路2、厦大2（4~5 分）→ 动态均分有数据
- 打卡 27 条：用户 1/2/1010/1011/1012 各 5~6 个杭州景点；用户 1/1011/1012 打卡厦门（鼓浪屿/环岛路/南普陀/沙坡尾）→ 来过统计+足迹有数据
- 赞/收藏：既有功能（attraction:liked: Redis / tb_favorite），本次未加 seed（打卡/评分/评论 seed 已齐全）

## 改动文件
- 新增（12）：entity/AttractionComment.java、entity/AttractionRating.java、entity/AttractionCheckin.java；mapper/AttractionCommentMapper.java、mapper/AttractionRatingMapper.java、mapper/AttractionCheckinMapper.java；service/IAttractionCommentService.java、service/impl/AttractionCommentServiceImpl.java；controller/AttractionCommentController.java
- 修改（11）：entity/Attraction.java（destination + avgScore/ratingCount/myRating/checkedIn/checkinCount）、entity/TravelNote.java（attraction）；service/IAttractionService.java、service/impl/AttractionServiceImpl.java；controller/AttractionController.java（/rate、/rate/status、/checkin/{id}、/destinations、/of/destination）；service/IUserService.java、service/impl/UserServiceImpl.java、controller/UserController.java（/checkins/{id}）；service/ITravelNoteService.java、service/impl/TravelNoteServiceImpl.java；resources/db/travelplus.sql

## 验证
- `mvn -q compile` → EXITCODE=0；运行库 COUNT 校验：attractions=26（杭州20/厦门6）、comments=22、ratings=28、checkins=27
- travelplus.sql 全量灌入临时库 travelplus_seedtest 验证通过（已删除临时库）；未重启后端；未 git 操作；未改前端；未加新依赖

## 前端对接契约
- 景点评论：POST /attraction-comment {attractionId, content}（需登录）；GET /attraction-comment/of/{attractionId}?current=（需登录，倒序含 name/icon）
- 动态评分：POST /attraction/rate {attractionId, score 1~5}（需登录，可改分）；GET /attraction/rate/status?attractionId=&userId=（需登录）；景点详情新增 avgScore（×10，/10 展示）/ratingCount/myRating
- 打卡足迹：POST /attraction/checkin/{id}（需登录，幂等）；景点详情新增 checkedIn/checkinCount；GET /user/checkins/{id}?current=（需登录）→ {id,name,image,x,y,checkinTime}
- 城市推荐：GET /attraction/destinations（公开）→ [杭州,厦门]；GET /attraction/of/destination?city=&current=（公开）
- 游记关联：POST /note body 可带 attractionId（可选，非 null 才校验存在）；GET /note/{id} 详情新增 attraction {id,name,image}
