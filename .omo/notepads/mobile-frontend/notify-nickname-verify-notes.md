
## 通知动作化 + 实时昵称 验证记录（2026-08-08 14:2x，后端重启后）

**验证对象**：TravelPlus 后端 8081（重启：杀 9156/20276 → start-backend.bat → 新进程 25628/46328）

### 结果总览
| 项 | 结果 | 关键响应 |
|----|------|----------|
| A. 重启后端 | ✅ | 8081 新进程 46328 LISTENING，未登录访问接口返回 401/403（登录拦截生效） |
| B1. content 纯动作 | ✅ | SELECT id,type,content FROM tb_notify ORDER BY id DESC LIMIT 8：全部为纯动作模板，无昵称前缀。例：id=217 comment「评论了你的游记《杭州西溪湿地…》」、id=215 like「赞了你的游记《…》」、id=216 favorite「收藏了你的游记《…》」 |
| B2. displayText 实时昵称（改名前） | ✅ | 登录 T2(13838411438) → GET /notify/list，fromUser.id=1 的 6 条通知 displayText 均以「小鱼同学 」开头。例：id=15「小鱼同学 收藏了你的游记《西湖断桥…》」 |
| B3. 改昵称 | ✅ | T1(13686869696) PUT /user/info {nickName:"小鱼同学改名"} → success=true；DB 确认 nick_name=小鱼同学改名 |
| B4. 改昵称后实时生效 | ✅ | T2 再 GET /notify/list，**同一条 id=15** displayText 变为「小鱼同学改名 收藏了你的游记《西湖断桥…》」——历史通知实时显示新昵称 |
| B5. 恢复昵称 | ✅ | T1 PUT /user/info {nickName:"小鱼同学"} → success=true；DB 确认还原；T2 再查 id=15 displayText 恢复「小鱼同学 收藏了…」 |

### 结论
- content 列只存纯动作模板（如「赞了你的游记《…》」），昵称不落库。
- displayText = 实时昵称 + " " + content，由 NotifyServiceImpl.queryNotifyList 每次查询实时拼装（fromUser.getById 取昵称），改昵称后历史通知自动显示新昵称（小红书方案）。✅ 全部通过。
- 后端保持运行中（供 orchestrator 浏览器 E2E），未做 git 操作。
