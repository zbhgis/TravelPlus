# 通知数据补真 + 客服会话落库 — 后端实现笔记

> 位置：`E:\AAAproject\travelplus`（Spring Boot 3.5.9 / MyBatis-Plus / MySQL 8 / Redis）
> 日期：2026-08-08 ｜ 状态：已实现，`mvn -q compile` 通过（EXIT=0，仅 JDK sun.misc.Unsafe 无害警告），运行库已应用；
> 未重启后端（orchestrator 统一重启）、未 git 操作、未改前端、未加新依赖

## 一、通知历史数据补真【消息数据补真的实现】

### 背景
消息页「新增关注 / 赞和收藏 / 评论和@」分类数量此前只有运行期即时产生的通知，与主页统计
（fans / followee / likedCount，见 `UserServiceImpl.queryUserHome`）对不上——例：用户1 关注4/粉丝4/获赞11，
但 follow 通知只有 2 条、like 通知 0 条。

### 补真策略（按真实数据关系生成历史通知，幂等，不含测试残留）
- **follow**：`tb_follow` 每条真实关注关系 → 被关注者一条 `follow` 通知
  （type=follow, user_id=被关注者, target_id=被关注者, content=`{关注者昵称}关注了你`, create_time=关注时间）
- **like**：每条真实点赞（Redis ZSet `note:liked:{noteId}` 成员，导出临时表 `tb_backfill_like` 后全量补）
  → 游记作者一条 `like` 通知（content=`{昵称}赞了你的游记《{标题前20字}》`，标题>20字截断+…）；
  **排除自赞**（生产 `NotifyServiceImpl.add` 对自触发跳过，自赞不产生通知）
- **favorite**：`tb_favorite` 中 target_type='note' 每行 → 游记作者一条 `favorite` 通知（`{昵称}收藏了你的游记《…》`）
- **comment / reply**：`tb_travel_note_comment` parent_id=0 每条一级评论 → 游记作者一条 `comment` 通知；
  reply（parent_id!=0）→ 被回复评论作者（当前库全部为一级评论，reply 分支语句保留备未来）
- **mention/@**：按 @解析规则扫描评论，当前评论无 @ 引用，无需补
- **幂等**：全部 `INSERT ... SELECT ... WHERE NOT EXISTS (同 user_id+from_user_id+type+target_id)`
  保证重复执行不重复插；create_time 取真实关系时间（关注时间/评论时间/收藏时间/游记创建时间作为点赞发生时间代理）

### 去重（运行期重复触发清理）
运行期"点赞-取消-再点赞 / 评论-删除-再评论"会产生完全一致的通知重复（如用户1赞note5出现 3 条相同
like 通知、id=2/14/19）。按 `(user_id, from_user_id, type, target_id, content)` 语义删除 id 较大者、
保留最早一条，共清理 5 条，保证计数真实。

### 补真后一致性（实时查运行库，全 13 用户）
- **follow 通知数 == 粉丝数**：全部用户 ✓（用户1=4/4）
- **like 通知数 == SUM(liked) - 自赞数**：全部用户 ✓（用户1 获赞11 → like 通知 9，差 2 = 用户1 给自己 2 篇游记的自赞）
  - 说明：获赞数=赞我游记的总数（含自己的赞），like 通知=每笔他人赞（生产 add() 不产生自通知）；
    故消息页「赞和收藏」类目数量=他人赞数，与主页获赞存在"自赞"差异，此为真实语义
- **favorite**：与 tb_favorite（target_type=note）一一对应；个别历史 favorite 通知（用户曾收藏后又取消）
  保留——取消收藏/取消赞不删通知是系统既有语义
- **comment / mention**：对应真实评论 + 少量历史操作通知（曾评论后删除评论，通知按既有语义保留）
- tb_notify 现状：follow=28 / like=101 / favorite=5 / comment=19 / reply=2 / mention=2（共 157 条）
- tb_notify 为运行时业务数据，种子脚本不写入（延续既有约定，travelplus.sql 未加通知数据）

## 二、客服会话落库【客服会话的实现】

### 客服虚拟用户（运行库已插 + 种子已同步）
- `INSERT tb_user`：id=**1021**（固定，代码常量 SUPPORT_USER_ID）、phone=`10000000000`（特殊号）、
  password=''（不可登录）、nick_name=`TravelPlus客服`、icon=`/imgs/user/avatar/default.png`（系统默认头像）、
  bg_image=`/imgs/user/bg/1.jpg`（复用已存在背景图避免破图）
- 配套 `tb_user_info`（user_id=1021，introduce=TravelPlus 智能客服…）使用户主页/会话可正常渲染
- `travelplus.sql` 已同步：tb_user 与 tb_user_info 各一条（带【客服会话的实现】注释）

### SupportServiceImpl.chat 落库
- 注入 `ChatMessageMapper` + `UserMapper`（直接注入 Mapper 而非 IChatService/IUserService，避免循环依赖，
  风格同 ChatServiceImpl 注入 FollowMapper）；构造器注入随 AiProperties 一并扩展
- AI 回复**成功后** `persistConversation(userId, message, reply)` 双向写 `tb_chat_message`：
  - 用户消息：from=当前用户 → to=1021，is_read=1（自己发出即视为已读，不计未读）
  - 客服回复：from=1021 → to=当前用户，is_read=0（用户未读，计入私信未读数角标）
  - 用户消息存原文 message（不含拼给模型的"（当前用户 id：x）"后缀），记录真实
- **容错**：userId 为空（理论不发生，/support/chat 需登录）或当前用户/客服用户被删 → log.warn 跳过落库，
  仍正常返回 AI 回复（客服为虚拟用户必存在）；落库异常 catch → log.warn，绝不阻断回复
- 效果：客服对话走私信消息表 → 消息栏「私信」会话列表（ChatServiceImpl.sessions 聚合）自然出现
  "TravelPlus客服"会话（peer 昵称/头像来自 tb_user），前端 `/chat/list?peerId=1021` 即可拉取双向完整历史
- 注意：本改动需后端重启后生效（orchestrator 统一重启，本任务未重启）

## 三、验证
- `mvn -q compile` → BUILD SUCCESS（EXIT=0，仅 JDK sun.misc.Unsafe 无害警告，无代码错误）
- 一致性 SQL 校验：全用户 follow 通知==粉丝数、like 通知==获赞数-自赞 ✓；去重后无 (user,from,type,target) 重复
- tb_chat_message schema（from_user_id/to_user_id/content/is_read/create_time）与 ChatMessage 实体字段一致，insert 可用
- 未重启后端、未 git 操作、未改前端、未加新依赖

## 改动文件
- `service/impl/SupportServiceImpl.java`（【客服会话的实现】持久化 + 注释）
- `src/main/resources/db/travelplus.sql`（客服用户种子 id=1021）
- 运行库 SQL（临时脚本保留在 `C:\Users\lenovo2026\AppData\Local\Temp\opencode\`）：
  `notify_backfill.sql`（通知补真）、`dedupe.sql`（重复通知清理）、`support_user.sql`（客服用户）

## 契约要点（供前端对接）
- 消息页「新增关注」数量=follow 通知数（== 粉丝数）；「赞和收藏」=like+favorite 通知数（== 他人赞数+收藏数）；
  「评论和@」=comment/reply/mention 通知数
- 客服会话：peerId=1021（TravelPlus客服）；`/chat/list?peerId=1021` 拉取历史（返回时自动把客服发的未读置读）