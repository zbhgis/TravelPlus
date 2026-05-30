# TravelPlus 个人主页编辑 + 浏览记录 — 实现摘要（orchestrator 统一测试用）

## 新增能力（mvn compile BUILD SUCCESS，未重启后端）
接口契约以本文件为准，前端编辑页/浏览记录页按此对接。

## ① 表结构变更（DDL 已执行 + travelplus.sql 种子脚本同步）
- `tb_user` 新增 `bg_image VARCHAR(255) NOT NULL DEFAULT ''`（背景图路径）
- `tb_user_info` 新增 `follow_visible TINYINT NOT NULL DEFAULT 1`、`fans_visible TINYINT NOT NULL DEFAULT 1`（关注/粉丝列表是否公开，1=可见）
- 实体同步：`User.bgImage`（默认""）、`UserInfo.followVisible/fansVisible`（Integer）、`UserDTO.bgImage`（默认""，主页回显）
- 种子脚本：tb_user 建表加 bg_image 列、tb_user_info 建表加两列（默认 1）；15 条 tb_user INSERT 补 `''`、11 条 tb_user_info INSERT 补 `1, 1`
- DDL 用 SQL 文件管道法执行：`docker exec -i travelplus-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 travelplus < file.sql`，已验证 3 列存在

## ② 上传接口（【图片上传的实现】）
- `POST /upload/user`（multipart 字段 file），与 /upload/note 同逻辑但存 `imgs/user/` 目录
- 返回 data=相对路径 `/user/{d1}/{d2}/{uuid}.ext`（不带 /imgs 前缀），前端拼 `IMG_BASE + /imgs + 路径`
- 头像/背景图共用本接口；`createNewFileName(originalFilename, dir)` 变体，note 传 "blogs"、user 传 "user"

## ③ 编辑资料接口（【用户主页编辑的实现】）
- `PUT /user/info`（需登录），body=`UserEditDTO`：nickName/icon/bgImage/introduce/city/gender/birthday/followVisible/fansVisible
- **只允许改自己**：UserHolder 当前用户，dto 不含 userId
- tb_user：nickName/icon/bgImage 非空才更新（部分保存不覆盖未传字段），同步 updateTime
- tb_user_info：不存在则创建（userId=当前用户，fans/followee/credits/level/隐私开关写默认值），存在则非空字段覆盖
- 校验：nickName≤20、gender∈{0,1}、followVisible/fansVisible∈{0,1}
- 成功后 Controller 用请求头 token 同步刷新 Redis 登录态 Hash（nickName/icon 字段），保证 /user/me 即时生效

## ④ 隐私校验（【隐私设置的实现】）
- `GET /follow/list/{id}` 在 service 层校验：following 看 follow_visible、fans 看 fans_visible
- visible=0 且访问者≠本人 → fail `"对方设置了隐私，关注/粉丝列表不可见"`；未登录访问他人隐藏列表同样拒绝
- user_info 不存在=默认公开（1）；本人查看自己列表不受限
- 隐私只影响【列表】，主页 /user/home/{id} 的 fans/followee【计数】仍实时展示（小红书风格）

## ⑤ 浏览记录（【浏览记录的实现】，Redis ZSet 免建表）
- key=`browse:{userId}`，member=`note:{id}` / `attraction:{id}`，score=System.currentTimeMillis()
- 去重：ZADD 同 member 只保留一份但更新 score=最近浏览时间；上限：超 50 条裁剪最旧（ZREMRANGEBYRANK，先 zCard 判大小再执行，避免不足 51 条时负索引全删）
- `GET /user/browse/list`（需登录）→ data=List<Map>：{type, id, title, image(首图), time(score 时间戳)}，ZREVRANGE 时间倒序；查不到的对象跳过
- 记录点：GET /note/{id}（queryTravelNoteById）→ record(userId,"note",id)；GET /attraction/{id}（queryById）→ record(userId,"attraction",id)；均仅在登录用户时记录

## 改动文件
- DDL + travelplus.sql（种子脚本同步）
- entity/User、entity/UserInfo、dto/UserDTO、dto/UserEditDTO（新增）
- controller/UserController（PUT /info + GET /browse/list）、controller/UploadController（POST /user）
- service/IUserService、service/impl/UserServiceImpl（updateUserInfo）
- service/impl/FollowServiceImpl（隐私校验，注入 IUserInfoService）
- utils/RedisConstants（BROWSE_RECORD_KEY/BROWSE_RECORD_MAX_SIZE）
- service/IBrowseRecordService、service/impl/BrowseRecordServiceImpl（新增）
- service/impl/TravelNoteServiceImpl、service/impl/AttractionServiceImpl（记录点注入 IBrowseRecordService）

## 鉴权边界（复用既有拦截器，未动）
- 需登录：PUT /user/info、GET /user/browse/list、GET /follow/list/{id}（均在既有 /user/**、/follow/** 拦截范围）
- 回显不变：GET /user/info/{id} 返回的 UserInfo 自动带 followVisible/fansVisible；GET /user/home/{id} 的 user 带 bgImage、info 带隐私字段

## 注意事项
- BrowseRecordServiceImpl 直接注入 TravelNoteMapper/AttractionMapper 而非 Service，避免与 TravelNoteServiceImpl/AttractionServiceImpl 互相注入 IBrowseRecordService 形成循环依赖
- 浏览记录不落 MySQL，仅 Redis（key 可配过期/淘汰策略）
