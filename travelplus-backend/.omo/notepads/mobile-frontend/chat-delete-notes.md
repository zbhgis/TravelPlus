# 私信单方删除（小红书式）改动摘要（mvn -q compile 通过，未重启后端，未 git 操作）

## A. 表结构（DDL 已执行 + 种子已同步）
- 线上 DDL（UTF-8 无 BOM 管道法执行，EXITCODE=0，SHOW COLUMNS 已确认）：
  `ALTER TABLE tb_chat_message ADD COLUMN is_deleted_by_me tinyint(4) NOT NULL DEFAULT 0 COMMENT '接收方已删除(仅接收方视角隐藏 0=正常 1=已删)' AFTER is_read;`
- travelplus.sql 种子同步：tb_chat_message 建表段新增 `is_deleted_by_me tinyint(4) NOT NULL DEFAULT 0` 列 + 注释说明
- 实体 ChatMessage 新增 `isDeletedByMe Integer` 字段（含【私信单方删除的实现】javadoc）

## B. 单方删除改造（【私信单方删除的实现】，小红书式）
- `ChatServiceImpl.deleteSession(peerId)` 由"双向物理删"改为单方删除：
  1. `UPDATE tb_chat_message SET is_deleted_by_me=1 WHERE from_user_id=peer AND to_user_id=我`——对方发给我的消息标记删除（软删，只隐藏我的视角，消息留在库中）
  2. `DELETE FROM tb_chat_message WHERE from_user_id=我 AND to_user_id=peer`——我发出的消息物理删（我方视角无残留）
  3. 对方视角不读 is_deleted_by_me 列 → 对方会话完整保留（他发给我 + 我发给他的都可见）
  4. 对方新发消息 is_deleted_by_me=0 → 重新出现在我的 list/sessions → 会话自动恢复显示
- `list(peerId)` 查询层过滤：`(from=me AND to=peer) OR (from=peer AND to=me AND is_deleted_by_me <> 1)`（NOT (to=me AND is_deleted_by_me=1)），我的视角不返回已删消息
- `sessions()` 聚合过滤：`from=me OR (to=me AND is_deleted_by_me <> 1)`——我删会话后对方消息不进聚合，该 peer 无可见消息 → 会话不显示；unread 累计排除已删（查询过滤 + 循环内双保险再判）
- `unreadCount()` 同步追加 `.ne("is_deleted_by_me", 1)`：我删除会话标记的对方消息不计入未读角标（与 list/sessions 的"我的视角"一致）

## C. 验证
- `mvn -q compile` → EXITCODE=0（无代码错误）
- DDL 已验证：SHOW COLUMNS 确认 is_deleted_by_me tinyint NOT NULL DEFAULT 0
- 未重启后端（orchestrator 统一）；未 git 操作；未改前端；未加新依赖；未动既有接口语义

## 改动文件
- entity/ChatMessage.java（+isDeletedByMe 字段）
- service/IChatService.java（deleteSession/list/sessions/unreadCount javadoc 同步单方删除语义）
- service/impl/ChatServiceImpl.java（deleteSession 改造 + list/sessions/unreadCount 过滤）
- controller/ChatController.java（类注释 + deleteSession javadoc 同步）
- src/main/resources/db/travelplus.sql（+is_deleted_by_me 列）

## 契约要点（供前端对接）
- 删除会话（DELETE /chat/session/{peerId}）语义变化：只删我这一侧——我的会话列表/历史中该 peer 消失；对方会话不受影响
- 对方再发新消息 → 我的会话自动恢复显示（无需任何手动操作）
- list/sessions 返回的消息结构不变（ChatMessage 新增 isDeletedByMe 字段，前端可忽略）
- 可选：前端删除确认文案可提示"仅删除我的会话记录"
