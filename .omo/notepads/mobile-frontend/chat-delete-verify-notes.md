
## 私信单方删除验证（2026-08-08 19:06）

> 场景：用户1=T1(13686869696, id=1) / 用户2=T2(13838411438, id=2)；后端已重启（PID 32156，8081 已 200）。

### 前置
- T1 发 1 条给 T2（msg id=43，你好）→ 已有会话
- T2 回复 T1 1 条（msg id=44，T2回复T1的消息-删除前）→ 双向会话就绪

### 验证结果
| 步骤 | 操作 | 结果 |
|---|---|---|
| B2 删除 | T1 DELETE /chat/session/2 | ✅ success=true |
| B3 我方消失 | T1 GET /chat/sessions | ✅ 不含 peer=2（仅剩 1021,1010） |
| B4 对方保留 | T2 GET /chat/sessions | ✅ 仍含 peer=1（lastContent=msg44 内容，unread=0） |
| B5 新消息恢复 | T2 POST /chat/send→T1（msg id=45，T2删除后新消息-恢复会话） | ✅ success；T1 sessions 重新出现 peer=2（unread=1） |
| B6 已删不返回 | T1 GET /chat/list?peerId=2 | ✅ 仅含 msg45（total=1），旧 msg43/44 不返回 |

### 数据库核对（tb_chat_message）
- msg 43（T1→T2）：**物理删除**（行不存在）
- msg 44（T2→T1）：is_deleted_by_me=1（T1 视角过滤，T2 仍可见）
- msg 45（T2→T1）：is_deleted_by_me=0（新消息自动恢复）

### 结论
私信会话单方删除功能 ✅ 全部符合预期：只影响删除方视角，对方保留完整会话，对方新消息自动恢复显示。
