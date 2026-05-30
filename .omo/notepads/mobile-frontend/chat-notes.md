# 私信功能（互关限制）改动摘要（mvn -q compile 通过，未重启后端，未 git 操作）

## A. 表结构（DDL 已执行 + 种子已同步）
- `CREATE TABLE IF NOT EXISTS tb_chat_message`（UTF-8 无 BOM 管道执行）：
  - id BIGINT UNSIGNED PK AUTO_INCREMENT / from_user_id（发送者）/ to_user_id（接收者）/ content varchar(500) / is_read tinyint default 0（0未读 1已读）/ create_time timestamp default now
  - 索引：`idx_from_to(from_user_id,to_user_id,id)`（双向会话分页）、`idx_to_read(to_user_id,is_read)`（未读数+置读）
- travelplus.sql 种子同步：tb_follow 段后新增 tb_chat_message 建表段（`CREATE TABLE IF NOT EXISTS`，注释"运行时业务数据，种子不写入"）

## B. 私信功能（【私信功能的实现】）
- 新实体 ChatMessage（@TableName tb_chat_message）+ ChatMessageMapper + IChatService + ChatServiceImpl + ChatController（@RequestMapping("/chat")，需登录，不在 MvcConfig exclude 列表）
- 接口：
  - `POST /chat/send` body {toUserId, content}：校验接收者非空且不能是自己 → content 非空 ≤500 → 接收用户存在（getById 判空 fail"接收用户不存在"）→ 互关限制 → 插入 is_read=0 消息，返回 data=消息 id
  - `GET /chat/list?peerId=&current=`：双向条件 `(from=me AND to=peer) OR (from=peer AND to=me)` 按 create_time+id 倒序分页（MAX_PAGE_SIZE）；**返回时批量置读** `UPDATE tb_chat_message SET is_read=1 WHERE to_user_id=me AND from_user_id=peer AND is_read=0`（覆盖不在本页的更早未读，翻看即已读），并把当前页"对方发给我"的 isRead 置 1 保证返回一致；data=List{ChatMessage{id,fromUserId,toUserId,content,isRead,createTime}}，total=总数
  - `GET /chat/sessions`：一条 SQL 取全部 (from=me OR to=me) 消息 create_time 倒序 → 内存按"对方 id"分组（LinkedHashMap 保序=最后消息时间倒序，先出现者=lastContent/lastTime），未读数累计"对方发我且未读"，一次 userService.listByIds 批量补昵称/头像（无 N+1）；每条含 peer{id,nickName,icon}, lastContent, lastTime, unread，按 lastTime 倒序
  - `GET /chat/unread-count`：data=我收到的未读总数（count null 兜底 0）
  - `GET /chat/mutual-status?peerId=`：data={mutual:boolean}（供前端展示限制提示）

## C. 互关限制（【私信限制的实现】，小红书式）
- 互关判定：`SELECT COUNT(*) FROM tb_follow WHERE (user_id=me AND follow_user_id=peer) OR (user_id=peer AND follow_user_id=me)`（一条 OR SQL，少一次往返；tb_follow 有 uk_user_follow 唯一约束最多两条，count==2 → mutual=true）
- 限制规则：非互关时每天（自然日）A→B 最多 3 条，超出 fail"对方未关注你，今日私信次数已用完"；互关豁免（不计数不限制）
- 实现：Redis key `chat:limit:{fromUserId}:{toUserId}:{yyyyMMdd}`（RedisConstants 新增 CHAT_LIMIT_KEY / CHAT_NON_MUTUAL_DAILY_LIMIT=3）；发送前 INCR，返回 1=当日首次随即 EXPIRE 当日剩余秒数（自然日 TTL 自动重置，无需定时任务）；INCR 返回 null（Redis 异常）按放行处理
- 为什么限 3 条/天：未互关=对方未主动建立双向关系，连续单向私信属潜在骚扰；3 条/天保留沟通通道又把骚扰成本降到可忽略

## D. 验证
- `mvn -q compile` → EXITCODE=0（仅 JDK sun.misc.Unsafe 自身告警，无代码错误）
- DDL 已验证：SHOW CREATE TABLE 确认表/索引/注释正确
- 未重启后端；未 git 操作；未改前端；未加新依赖；未动既有接口

## 改动文件
- entity/ChatMessage.java（新）、mapper/ChatMessageMapper.java（新）
- service/IChatService.java（新）、service/impl/ChatServiceImpl.java（新）
- controller/ChatController.java（新）
- utils/RedisConstants.java（+CHAT_LIMIT_KEY、+CHAT_NON_MUTUAL_DAILY_LIMIT）
- src/main/resources/db/travelplus.sql（+tb_chat_message 建表段）

## 契约要点（供前端对接）
- 私信消息元素字段：id, fromUserId, toUserId, content, isRead(0/1), createTime（list 返回为 ChatMessage 实体序列化）
- list 倒序=最新在前，前端倒过来正序展示；翻看会话自动置读，未读数随之归零
- sessions 元素字段：peer{id,nickName,icon}, lastContent, lastTime, unread（按 lastTime 倒序）
- mutual-status 返回 {mutual:boolean}；mutual=false 时前端提示"对方未关注你，今日仅可发送 3 条私信"
