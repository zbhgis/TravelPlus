# 关注Feed实时查询 + 自触发通知 + 客服免私信限制 — 实现摘要
# mvn -q compile 通过（EXITCODE=0，仅 Maven 自身 sun.misc.Unsafe JDK24 无害告警）；未重启后端；未 git 操作；未改前端；未加新依赖

## ① 关注 Feed 改实时查询【关注Feed的实现】
- **现状问题**：`queryTravelNoteOfFollow` 原用 Redis ZSet `feed:{userId}` 推送制——只在"发布游记时"推给当时粉丝 + 关注时补齐历史，
  二者都依赖发布时点，历史关注者游记易缺失/不全，首页关注只显示少数人。
- **改造（实时查询）**：
  - 接口签名：`ITravelNoteService.queryTravelNoteOfFollow(Long max, Integer offset)` → `(Integer current)`（页面参数），
    `TravelNoteController` 同步改为 `@RequestParam("current") Integer current`（前端同步改；lastId/offset 旧契约废弃）
  - 实现：① 一次查 `tb_follow WHERE user_id=me` 取关注对象集合（follow_user_id，避免 N+1）；
    ② 无关注直接返回空列表（`Result.ok(emptyList, 0L)`）；③ `IN(user_id, 关注集合)` 查游记并过滤可见性
    ——他人隐藏笔记（visible=1）不出现，仅保留公开（visible=0）或作者本人（`visible = 0 OR user_id = 当前用户`），
    作者本人始终可见；④ `create_time` 倒序 + `id` 倒序二级排序（同时间发布翻页稳定）分页 `Page(current, MAX_PAGE_SIZE=10)`；
    ⑤ 复用 `queryTravelNoteUser`（作者 name/icon）与 `isTravelNoteLiked`（当前用户点赞态）。
  - 返回：`Result.ok(list, total)`（data=List<TravelNote>，total=符合条件总数）
- **注释要点**：为什么改实时查询（推送制依赖发布时点→历史关注者游记缺失；实时 join 关注列表+游记，数据与我关注人数一致，
  无缓存一致性问题）、visible 过滤、分页、避免 N+1（关注者一次查、游记批量 IN）
- **推流保留**：`saveTravelNote` 的 Feed 推流与 `FollowServiceImpl.backfillFollowFeed` 保留为历史实现（不删，注释标注已不被读取端消费）

## ② 自赞/自收藏/自评论通知【消息数据补真的实现】增强
- **根因**：自触发过滤集中在 `NotifyServiceImpl.add` 的 `userId.equals(fromUserId)` 分支，导致自赞/自收藏/自评论/@自己 全部不发通知
- **改造（self-notify）**：
  - `NotifyServiceImpl.add`：移除 `userId.equals(fromUserId)` 排除——接收者==触发者同样落库通知（用户要求消息页显示
    "自赞/自收藏/自评论"），仅 null id / 空 type/content 脏调用跳过；类注释与方法注释同步更新
  - `TravelNoteServiceImpl.notifyNoteAuthorLiked`（likeTravelNote 旁路）：自赞→自己收 like 通知，文案 `{昵称}赞了你的游记《…》`
  - `FavoriteServiceImpl.notifyTargetAuthor`（favorite 旁路）：自收藏→自己收 favorite 通知
  - `TravelNoteCommentServiceImpl`：自评论（作者==评论者）→自己收 comment 通知；自回复→自己收 reply 通知；
    `notifyMentionUsers` 移除"排除自己"分支——@ 到自己昵称同样通知自己（mention）；与评论/回复接收者重复时仍合并去重
- **注释要点**：通知含自触发（self-notify），满足"消息页显示自赞/自收藏/自评论"

## ③ 客服不受私信限制【AI客服的实现】确认
- **确认结果**：`SupportController` 客服对话走独立 `/support/chat` 链路，直接调 `supportService.chat(message, userId)`
  （内部走 DeepSeek 大模型），**不经过 `ChatServiceImpl.send` 的互关/陌生人限制逻辑**（dm_mode 三档 + 陌生人回复前每日限条）——
  `SupportServiceImpl` 只注入 `ChatMessageMapper`/`UserMapper`/`AiProperties`，未注入 `IChatService`，天然不受私信限制
- **改动**：仅在 `SupportController`（类注释 + 接口）与 `SupportServiceImpl`（类注释 + chat 方法）补注释明确说明
  "客服会话不受私信限制约束（独立接口，不走 /chat/send 限制逻辑）"；落库 tb_chat_message 仅为会话展示，不触发限制校验

## 验证
- `mvn -q compile` → EXITCODE=0（仅 Maven 自身 JDK24 sun.misc.Unsafe 告警，与本代码无关）
- 未重启后端（orchestrator 统一）；未做任何 git 操作；未改前端；未加新依赖

## 改动文件
- `service/ITravelNoteService.java`：queryTravelNoteOfFollow 签名 + 接口注释
- `controller/TravelNoteController.java`：/note/of/follow 参数改 current + 注释
- `service/impl/TravelNoteServiceImpl.java`：queryTravelNoteOfFollow 实时查询实现 + like 自赞注释 + saveTravelNote 推流注释
- `service/impl/NotifyServiceImpl.java`：移除自触发排除
- `service/impl/FavoriteServiceImpl.java`：自收藏通知注释
- `service/impl/TravelNoteCommentServiceImpl.java`：自评论/自@ 通知注释 + notifyMentionUsers 移除排除自己
- `controller/SupportController.java`、`service/impl/SupportServiceImpl.java`：客服不受私信限制注释

## 契约要点（供前端对接）
- 关注频道：`GET /note/of/follow?current=页码`（**需登录**）→ data=List<TravelNote>{id,userId,name,icon,title,images,content,liked,comments,createTime,isLike}，
  total=总数；每页 10 条，createTime 倒序；**不再有 lastId/offset/ScrollResult**
- 消息页「赞和收藏」「评论和@」分类现在会包含自己的自触发通知（自赞/自收藏/自评论/@自己）
- 客服：`POST /support/chat` 不受私信限制，任何已登录用户可随时咨询

