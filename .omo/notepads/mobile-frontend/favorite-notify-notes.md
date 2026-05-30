# 收藏功能 + 消息分类过滤 改动摘要（mvn -q compile 通过，未重启后端，未 git 操作）

## A. 收藏表结构（DDL 已执行 + 种子已同步）
- `CREATE TABLE IF NOT EXISTS tb_favorite`（id/user_id/target_type/target_id/create_time，唯一键 `uk_user_target(user_id, target_type, target_id)`，UTF-8 无 BOM 管道法执行，空数据）
- travelplus.sql 种子同步：tb_follow 段后新增 tb_favorite 建表段（注释"运行时业务数据，种子不写入"）
- DDL 已验证：`SHOW CREATE TABLE travelplus.tb_favorite` 确认唯一键 uk_user_target 存在

## B. 收藏功能（【收藏功能的实现】）
- 新实体 Favorite（@TableName tb_favorite）+ FavoriteMapper + IFavoriteService + FavoriteServiceImpl
- `POST /favorite`（需登录，body {targetType, targetId}）：
  - 校验 targetType ∈ {note, attraction}（非法 fail）；目标必须存在（note 查 tb_travel_note、attraction 查 tb_attraction，不存在 fail"收藏的目标不存在"）
  - **幂等**：直接 INSERT，唯一键冲突（DuplicateKeyException）=已收藏，捕获返回 ok（不重复落库/不重复通知/不报错）
  - **通知目标作者**：仅首次收藏成功发 type=favorite 通知——note 查 tb_travel_note.user_id，文案 `{昵称}收藏了你的游记《{标题前20字}》`；**attraction 无作者字段直接跳过**；旁路 try-catch 静默
- `DELETE /favorite/{targetType}/{targetId}`（需登录）：按 (user_id, target_type, target_id) 条件删除，天然幂等
- `GET /favorite/list?type=&current=`（需登录）：当前用户该类型收藏分页（create_time 倒序，MAX_PAGE_SIZE）；**join** note→title/images 首图、attraction→name/images 首图，返回 List<Map{id, targetType, targetId, title, image, createTime}>，目标已删除跳过该条；total=总数
- `GET /favorite/status?type=&id=`（需登录）：data=boolean 是否已收藏（实时 COUNT）
- `GET /favorite/count?type=&id=`（公开）：data=收藏数（count null 兜底 0）
- 鉴权：/favorite/** 不在 MvcConfig exclude，LoginInterceptor 拦截需登录（与 /notify/** 同策略）

## C. 详情增强（【收藏功能的实现】）
- TravelNote/Attraction 实体新增 `@TableField(exist=false) isFavorite(Boolean)/favoriteCount(Integer)`
- `TravelNoteServiceImpl.queryTravelNoteById` / `AttractionServiceImpl.queryById`：注入 **FavoriteMapper 直查**（避免与 FavoriteServiceImpl 循环依赖），详情返回前补充：
  - favoriteCount：实时 COUNT(target_type+target_id)，无缓存
  - isFavorite：与 isLike 同模式——仅登录用户判断（UserHolder 判空），**未登录保持 null**
- /note/{id}、/attraction/{id} 匿名可访问，游客详情 isFavorite=null、favoriteCount=实时数

## D. 消息分类过滤（【消息分类的实现】）
- `INotifyService` 新增重载 `queryNotifyList(Integer current, String category)`；原 `queryNotifyList(current)` 委托 category=null（**兼容旧调用**）
- `GET /notify/list?current=&category=`（category 可选，不传查全部）三分类映射（小红书式消息 Tab）：
  - `likes` → type IN ('like','favorite')  赞和收藏（收藏通知 type=favorite 并入此类）
  - `follows` → type IN ('follow')  新增关注
  - `comments` → type IN ('comment','reply','mention')  评论和@（一级评论/回复/@ 合并）
  - 非法 category → fail"category 参数非法"
- 其余逻辑不变：create_time 倒序分页 + fromUser{id,nickName,icon} 补充 + total

## E. 验证
- `mvn -q compile` → BUILD SUCCESS（EXITCODE=0；仅 Maven 自身 sun.misc.Unsafe JDK 告警，非代码问题）
- DDL 已验证 tb_favorite 建表 + 唯一键；未重启后端；未 git 操作；未改前端；未加新依赖
- 注：Java LSP（jdtls）未安装，编译通过为权威验证（沿用先前任务约定）

## 改动文件
- entity/Favorite.java（新）、mapper/FavoriteMapper.java（新）
- service/IFavoriteService.java（新）、service/impl/FavoriteServiceImpl.java（新）
- controller/FavoriteController.java（新）
- entity/TravelNote.java（isFavorite/favoriteCount）、entity/Attraction.java（isFavorite/favoriteCount）
- service/impl/TravelNoteServiceImpl.java（attachFavoriteInfo + FavoriteMapper）、service/impl/AttractionServiceImpl.java（attachFavoriteInfo + FavoriteMapper）
- service/INotifyService.java（category 重载）、service/impl/NotifyServiceImpl.java（分类过滤）、controller/NotifyController.java（category 参数）
- src/main/resources/db/travelplus.sql（tb_favorite 建表段）

## 契约要点（供前端对接）
- 收藏：POST /favorite {targetType: note|attraction, targetId}；DELETE /favorite/{targetType}/{targetId}；GET /favorite/list?type=&current=（返回 {id, targetType, targetId, title, image, createTime}）；GET /favorite/status?type=&id=→boolean；GET /favorite/count?type=&id=→number
- 详情增强：/note/{id}、/attraction/{id} 的 data 新增 isFavorite（登录用户才有值，未登录 null/缺省）+ favoriteCount
- 消息分类：/notify/list 新增 category=likes|follows|comments（不传=全部）；通知 type 新增 favorite（语义：收藏了你的游记），归入"赞和收藏"Tab
