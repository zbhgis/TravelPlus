# 用户主页分 tab 接口 — 实现摘要（mvn -q compile 通过，未重启后端，未 git 操作，未改前端）

## ① 主页评论列表（【主页评论列表的实现】）
- `GET /user/comments/{id}?current=`（**需登录**，/user/** 拦截）
- 归属用户校验：getById(userId) 查不到 → fail"用户不存在"
- 分页：tb_travel_note_comment WHERE user_id=id，create_time 倒序，每页 MAX_PAGE_SIZE(10)，覆盖全部评论（含一级+回复，平铺不树）
- join：逐条 noteMapper.selectById 取游记 title + images 首图；游记已删除跳过该条
- data = List<Map{id, noteId, noteTitle, noteImage, content, createTime}>，total=评论总数
- 注入 commentMapper/noteMapper（非 Service），避免与 TravelNoteCommentServiceImpl（注入 IUserService）循环依赖

## ② 收藏列表支持按用户查（【收藏功能的实现】增强）
- `GET /favorite/list?type=note|attraction&current=&userId=`（**需登录**，/favorite/** 拦截）
- **userId 可选**：缺省(null)=当前登录用户本人收藏页；传入=查看该用户收藏（**他人主页收藏 tab**）
- 传入 userId 时经 UserMapper 直查 tb_user 校验存在（**注意**：FavoriteServiceImpl 泛型是 Favorite，getById 查的是收藏表，必须用 UserMapper），不存在 → fail"用户不存在"
- 返回结构不变：data=List<Map{id, targetType, targetId, title, image, createTime}>，total=总数
- 契约要点：本人收藏页不传 userId；他人主页收藏 tab 传 userId=目标用户 id

## ③ 景点点赞（【景点点赞的实现】）
- `PUT /attraction/like/{id}`（**需登录**）
- 鉴权关键改动：MvcConfig 把 `/attraction/**` 整段放行细化为三条匿名路径——`/attraction`（POST/PUT 新增更新）、`/attraction/*`（GET 详情）、`/attraction/of/**`（类型/名称分页），使 `PUT /attraction/like/{id}` 落入 LoginInterceptor 拦截范围（未登录 401），其余景点查询保持匿名
- Redis 常量新增 `ATTRACTION_LIKED_KEY = "attraction:liked:"`（ZSet，member=userId，score=点赞时间戳）
- 切换语义与游记点赞一致：ZSet 含当前用户 → ZREM 取消；不含 → ZADD（score=System.currentTimeMillis()）
- **不做 DB 计数**：tb_attraction 无 liked 列（也不新增列），赞过判定/赞过列表全走 Redis ZSet
- Attraction 实体新增 `@TableField(exist=false) isLike(Boolean)`
- `queryById` 新增 attachLikeInfo：登录用户返回 isLike（ZSet score!=null），未登录保持 null
- service 内 UserHolder 判空兜底 fail"请先登录"

## ④ 赞过列表（【赞过列表的实现】）
- `GET /user/liked-notes/{id}?current=`（**需登录**）——赞过的游记
- `GET /user/liked-attractions/{id}?current=`（**需登录**）——赞过的景点
- 归属用户校验：getById 查不到 → fail"用户不存在"
- 数据来源：赞过记录存于各目标自己的点赞 ZSet（note:liked:{noteId} / attraction:liked:{attractionId}），以"用户"为维度汇总 → **遍历 KEYS 前缀**：`stringRedisTemplate.keys(NOTE_LIKED_KEY + "*")` / `ATTRACTION_LIKED_KEY + "*"`
- 判定+取时：每 key `opsForZSet().score(key, userId.toString())` 非空=赞过，score 即点赞时间戳；脏 key（后缀非数字）跳过
- **demo 规模遍历可行**（游记/景点数量有限），生产应换 SCAN 或反向索引/DB 表（注释已说明）
- 按点赞时间戳（score）倒序 → 内存手动分页（每页 MAX_PAGE_SIZE），from 越界返回空列表
- 逐条查目标取标题/首图：游记 title、景点 name（无 title 字段，以 name 作 title）；目标已删除跳过
- data = List<Map{id, title, image}>，total=赞过总数（已剔除目标删除项，与 data 一致）

## 改动文件
- utils/RedisConstants.java：ATTRACTION_LIKED_KEY
- entity/Attraction.java：isLike 非表字段
- service/IAttractionService.java、service/impl/AttractionServiceImpl.java：likeAttraction + attachLikeInfo
- controller/AttractionController.java：PUT /attraction/like/{id}
- config/MvcConfig.java：/attraction/** → /attraction + /attraction/* + /attraction/of/**（点赞需登录）
- service/IFavoriteService.java、service/impl/FavoriteServiceImpl.java（+UserMapper）、controller/FavoriteController.java：list 增加可选 userId
- service/IUserService.java、service/impl/UserServiceImpl.java（+commentMapper/attractionMapper）：queryUserComments / queryLikedNotes / queryLikedAttractions
- controller/UserController.java：GET /user/comments/{id}、/user/liked-notes/{id}、/user/liked-attractions/{id}

## 验证
- `mvn -q compile` → BUILD SUCCESS（EXITCODE=0；仅 Maven 自身 sun.misc.Unsafe JDK 告警，非代码问题）
- 未重启后端；未 git 操作；未改前端；未加新依赖；未动 tb_travel_note 点赞逻辑（note:liked 继续用）；无表变更
- 注：Java LSP（jdtls）未安装，编译通过为权威验证（沿用先前任务约定）

## 前端对接契约
- 评论 tab：GET /user/comments/{id}?current= → {id, noteId, noteTitle, noteImage, content, createTime}
- 收藏 tab：GET /favorite/list?type=&current=（本人，不传 userId）或 +&userId={他人主页目标用户}
- 赞过-游记 tab：GET /user/liked-notes/{id}?current= → {id, title, image}
- 赞过-景点 tab：GET /user/liked-attractions/{id}?current= → {id, title, image}
- 景点详情 /attraction/{id}：data 新增 isLike（登录用户才有值，未登录 null/缺省）
- 景点点赞：PUT /attraction/like/{id}（需登录；再次点击=取消）
