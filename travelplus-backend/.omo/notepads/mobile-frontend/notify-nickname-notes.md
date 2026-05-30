# 通知实时昵称（小红书方案）改动摘要（mvn compile BUILD SUCCESS，未重启后端，未 git 操作）

> 方案：通知 content 只存**纯动作文案**（不含昵称前缀），展示层用**实时触发者昵称**拼装 displayText——
> 用户改昵称后历史通知自动显示新昵称（昵称不落库、展示实时拼装，小红书式）。

## A. content 动作化（5 处调用点）
- `FollowServiceImpl.notifyFollow`：content `{nickName}关注了你` → **`"关注了你"`**（删除原取昵称拼装逻辑）
- `FavoriteServiceImpl.notifyTargetAuthor`：content `{昵称}收藏了你的游记《…》` → **`"收藏了你的游记《{title前20字}》"`**（title 从 note.getTitle() 取，>20字截断+…，与点赞同款）
- `TravelNoteServiceImpl.notifyNoteAuthorLiked`：content `{昵称}赞了你的游记《…》` → **`"赞了你的游记《{title前20字}》"`**
- `TravelNoteCommentServiceImpl.notifyCommentRelated/notifyMentionUsers`：
  - comment → **`"评论了你的游记《{title前20字}》"`**（noteTitle() 截断）
  - reply → **`"回复了你的评论"`**
  - mention → **`"在评论中提到了你"`**
- 各调用点 javadoc/内联注释更新：content 为纯动作模板（不含昵称），昵称由展示层实时拼装
- 清理：`TravelNoteCommentServiceImpl.currentNickName()` 删除（已无调用者）；FavoriteServiceImpl/TravelNoteCommentServiceImpl 无用的 `UserDTO` import 删除

## B. 展示实时拼装（【通知实时昵称的实现】）
- `Notify` 实体新增 `@TableField(exist=false) private String displayText;`（只响应传输不落库，同 fromUser 模式）；content 字段注释改为"纯动作文案，昵称由展示层拼 displayText"
- `NotifyServiceImpl.queryNotifyList` 每条通知组装：
  - 触发者存在：`displayText = fromUser.nickName + " " + content`（实时昵称，不落库；改昵称自动生效）
  - 触发者不存在（已删号）：`displayText = content`（退化为纯动作，前端判空兜底）
- 类注释/方法注释同步补充 displayText 拼装说明

## C. 存量迁移（SQL 管道法，已执行）
- 临时文件 `notify_content_migrate.sql`（UTF-8）经 `cmd /c "docker exec -i travelplus-mysql mysql --default-character-set=utf8mb4 -uroot -p123456 travelplus < file.sql"` 执行
- 按动作关键词截取（LOCATE + SUBSTRING）：`赞了你的游记`/`收藏了你的游记`/`评论了你的游记`/`关注了你`/`回复了你的评论`/`在评论中提到了你`，WHERE LIKE 命中才截
- 兜底：不含任何动作关键词的行按 type 置为纯动作文案（like→赞了你的游记 等），保证无前缀残留
- 验证 `notify_verify.sql`：`prefix_residual = 0`（160 行全部纯动作，无"xx赞了"前缀）；`SELECT id,type,content LIMIT 10` 内容均为纯动作
- travelplus.sql 无 tb_notify seed（注释"运行时业务数据，种子不写入"），跳过 seed 同步

## D. 验证
- `mvn compile` → BUILD SUCCESS（EXITCODE=0，仅 JDK 24 下 Guice 的 sun.misc.Unsafe 运行时告警，无害、与代码无关）
- SQL 验证：tb_notify 存量 160 行 content 全部为纯动作文案，前缀残留 0
- 未重启后端（orchestrator 统一）；未 git 操作；未改前端；未引入新依赖

## 改动文件
- entity/Notify.java（新增 displayText 非表字段 + content 注释更新）
- service/impl/NotifyServiceImpl.java（queryNotifyList 拼装 displayText）
- service/impl/FollowServiceImpl.java、FavoriteServiceImpl.java、TravelNoteServiceImpl.java、TravelNoteCommentServiceImpl.java（content 纯动作化 + 注释 + 清理无用 import/方法）

## 对前端契约（并行开发）
- 通知列表新增字段 `displayText`（实时昵称 + 空格 + 纯动作 content），前端渲染优先取 `item.displayText`（messages.js 原用 item.content）
- content 仍返回（纯动作文案，可作兜底）；fromUser{id,nickName,icon} 不变
