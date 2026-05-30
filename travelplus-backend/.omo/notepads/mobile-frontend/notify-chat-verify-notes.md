
## 通知补真与客服会话落库验证（2026-08-08，后端重启后）
- 后端：杀 8081(PID 21292) → start-backend.bat 重启 → Tomcat 8081 2.859s 启动成功，/user/home/1 → 200 ✅
- 测试账号：T1=1（13686869696/123456，密码登录），token 经 authorization header

### 通知一致性（用户1）
- tb_user 中 nick_name='TravelPlus客服' → id=1021（phone=10000000000，不可登录）✅
- GET /notify/list?category=follows&current=1 → total=4（1013/2/1012/1011 关注）✅
- GET /user/home/1 → fans=4（与 follows 通知 4 条一致）✅
- GET /notify/list?category=likes → total=9；DB tb_notify type=like COUNT=9 一致 ✅
  - 获赞核对：note6=5赞（1自赞+4他人）、note7=6赞（1自赞+5他人）→ 总11 = 9他人+2自赞
  - like 通知 9 = 获赞11 - 自赞2，自赞不生成通知，一致性成立 ✅

### 客服会话落库（T1 → 客服1021）
- POST /support/chat {"message":"测试客服会话"} → success + AI 回复（"您好！我是 TravelPlus 智能客服…"）✅
- tb_chat_message 双向落库：from1→1021 计数 1→2（新 id15，is_read=1）；from1021→1 计数 1→2（新 id16 AI 回复，is_read=0）✅
- GET /chat/sessions → 含 peer=1021（TravelPlus客服），lastContent=本次 AI 回复，unread=1 ✅
- GET /chat/list?peerId=1021&current=1 → total=4，含用户消息 id15 与 AI 回复 id16，翻看后均 isRead=1 ✅
- 后端保持运行，无 git 操作
