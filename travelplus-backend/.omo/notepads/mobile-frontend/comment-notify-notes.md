# 评论增强 + 通知系统 改动摘要（mvn -q compile 通过，未重启后端，未 git 操作）

## A. 表结构（DDL 已执行 + 种子已同步）
- `ALTER TABLE tb_travel_note_comment ADD COLUMN is_top TINYINT NOT NULL DEFAULT 0 COMMENT '置顶标志，1=置顶';`（已通过 docker exec 管道执行）
- `CREATE TABLE IF NOT EXISTS tb_notify (...)`（id/user_id/from_user_id/type/target_id/content/is_read/create_time，UTF-8 无 BOM 管道执行，空数据）
- travelplus.sql 种子同步：tb_travel_note_comment 建表加 `is_top tinyint(1) NOT NULL DEFAULT 0` 列；18 条 INSERT 全部补 is_top=0 值；新增 tb_notify 建表段（注释"运行时业务数据，种子不写入"）

## B. 评论增强（【评论功能的实现】）
- 实体 TravelNoteComment 新增 `isTop`（Integer，对应 is_top 列，含置顶语义注释）
- `queryComments`：DB 排序 is_top desc + create_time asc → 内存 `sortComments` 平铺排序：
  - 一级评论（parent_id=0）置顶(is_top=1)优先，其余按 createTime 升序
  - 回复（parent_id!=0）跟在所属一级评论之后（按一级评论排序位置 + 同级 createTime 升序；父级不在当前页排最后）
  - 返回每条含 isTop；前端按 parentId 组装（不嵌套）
- `deleteComment(id)`（【评论删除的实现】）：评论作者 或 该游记作者（note.userId）可删，否则 fail"无权删除"；删除该评论 + 其直接回复（parent_id=id，两级结构不递归）；游记 comments 用 `GREATEST(COALESCE(comments,0)-N,0)` 同步 -（1+被删回复数）
- `topComment(id, top)`（【评论置顶的实现】）：仅游记作者可操作（否则 fail"无权操作"）；仅一级评论可置顶；同一游记最多置顶 3 条（MAX_TOP_COMMENT），超限先取消 id 最小的旧置顶
- Controller：`DELETE /note-comments/{id}`、`PUT /note-comments/top/{id}`（body {top: Boolean}，缺失 fail"参数不完整"）
- 注释覆盖：回复结构（parentId=一级评论id、answerId=被回复评论id）、置顶排序策略、删除权限（作者/游记作者）

## C. 通知系统（【通知系统的实现】）
- 新实体 Notify（@TableName tb_notify，含 fromUser 非表字段）+ NotifyMapper + INotifyService + NotifyServiceImpl
- `NotifyServiceImpl.add`：旁路静默（try-catch log.warn 绝不打断主流程）；接收者==触发者（自赞/自评/自@/自关注）或 type/content 空直接跳过
- 记录点（主流程成功后再通知）：
  - likeTravelNote：仅"未赞→点赞"发 type=like 通知游记作者，文案 `{昵称}赞了你的游记《{标题前20字}》`（标题>20字截断+…），取消赞不发
  - FollowServiceImpl.follow：仅关注发 type=follow 通知被关注者，文案 `{昵称}关注了你`，targetId=被关注者id
  - saveComment：一级评论（parentId=0）type=comment 通知游记作者（文案 `{昵称}评论了你的游记《…》`）；回复（parentId!=0）type=reply 通知被回复评论的作者（`{昵称}回复了你的评论`）
  - @ 解析：正则 `@([^\s@，。！？]+)` 提取昵称，去重后一次 `IN nick_name` 查 tb_user（避免 N+1），命中真实用户发 type=mention（`{昵称}在评论中提到了你`）；排除自己；与 comment/reply 接收者重复时合并（只发一条）
- 接口（NotifyController，@RequestMapping("/notify")，需登录，不在 exclude 列表）：
  - `GET /notify/list?current=`：分页（MAX_PAGE_SIZE）create_time 倒序；每条含 fromUser{id,nickName,icon}/type/targetId/content/isRead/createTime；total=总数
  - `GET /notify/unread-count`：data=未读数（count null 兜底 0）
  - `PUT /notify/read`：无 body=全部已读；body {id}=单条已读（带 user_id 条件，防越权）

## D. 验证
- `mvn -q compile` / `mvn compile` → BUILD SUCCESS（EXITCODE=0，无代码错误；JDK 自身告警忽略）
- DDL 已验证：is_top 列存在、tb_notify 建表成功（SHOW COLUMNS / SHOW CREATE TABLE 确认）
- 未重启后端；未 git 操作；未改前端；未加新依赖

## 改动文件
- entity/TravelNoteComment.java（isTop）、entity/Notify.java（新）、mapper/NotifyMapper.java（新）
- service/INotifyService.java（新）、service/impl/NotifyServiceImpl.java（新）
- service/ITravelNoteCommentService.java、service/impl/TravelNoteCommentServiceImpl.java（排序/删除/置顶/通知/@）
- service/impl/TravelNoteServiceImpl.java（likeTravelNote 通知）
- service/impl/FollowServiceImpl.java（follow 通知）
- controller/TravelNoteCommentController.java（delete/top）、controller/NotifyController.java（新）
- src/main/resources/db/travelplus.sql（is_top 列 + tb_notify 建表 + INSERT 补 0）

## 契约要点（供前端对接）
- 通知列表 `data[]` 元素字段：id, userId, fromUserId, type, targetId, content, isRead(0/1), createTime, fromUser{id,nickName,icon}
- type 枚举：like/follow/comment/reply/mention；targetId 语义：like/comment/reply/mention=游记id，follow=被关注用户id
- 评论列表排序已变：置顶一级评论 → 其余一级评论（时间升序）→ 各自回复（跟随父级）
