# 私信功能验证笔记（chat-verify）

> 验证日期：2026-08-07 | 方式：后端重启 + curl 全链路测试 | 结论：全部 ✅

## 环境
- 后端：localhost:8081（start-backend.bat 重启，/attraction-type/list 200 就绪）
- Redis：company-agent-redis（docker exec redis-cli）
- MySQL：travelplus-mysql:3307，库 travelplus
- 测试账号：T1=1（13686869696/123456）、T2=2（13838411438/123456）、1013（13712340001/123456）

## 互关状态确认
- 初始 tb_follow：1→2、1→1013 存在；2→1、1013→1 不存在 → 均非互关
- 为测互关豁免：T2 PUT /follow/1/true（→互关 1↔2）；1013 登录后 PUT /follow/1/true（→互关 1↔1013）
- 关注关系保留（社交演示数据）

## 互关豁免（T1→T2）
- GET /chat/mutual-status?peerId=2 → {"success":true,"data":{"mutual":true}} ✅
- 连发 3 条"你好，互关测试" → 全部 success，data=消息 id（1,2,3）✅ 不限次

## 非互关限制（T1→1013）
- GET /chat/mutual-status?peerId=1013 → {"success":true,"data":{"mutual":false}} ✅
- 连发 3 条（测试1/2/3）→ 全部 success（data=4,5,6）✅
- 第 4 条（测试4）→ {"success":false,"errorMsg":"对方未关注你，今日私信次数已用完"} ✅
- Redis：GET chat:limit:1:1013:20260807 = 4、TTL=28331s>0
  - ⚠️ 注意：为 4 而非 3 —— 实现为"INCR 先于判定"，被拒的第 4 次仍自增（INCR-then-check 语义），
    限流判定本身正确（第 4 条被拒）；测试后已 DEL 该 key
- 1013 关注 T1 后再发第 4/5 条 → 全部 success（data=7,8）→ 互关后限制解除 ✅

## 消息列表
- T1 GET /chat/list?peerId=1013&current=1 → 5 条倒序（最新在前），total=5 ✅
- T2 GET /chat/list?peerId=1&current=1 → 含 T1 的 3 条，isRead=1（翻看即已读）✅

## 会话列表 / 未读数
- T1 GET /chat/sessions → 含 peer 2（lastContent 你好，互关测试, unread=0）与 peer 1013（测试1, unread=0），按 lastTime 倒序 ✅
- T2 GET /chat/unread-count → T1 新发 1 条后 data=1 ✅（已读后归 0）
- T2 GET /chat/sessions → 含 peer 1（unread=1 未读角标）✅

## 参数校验
- toUserId=99999 → {"success":false,"errorMsg":"接收用户不存在"} ✅
- content="" → {"success":false,"errorMsg":"消息内容不能为空"} ✅
- 给自己发（toUserId=1）→ {"success":false,"errorMsg":"不能给自己发私信"} ✅
- 未登录访问 /chat/sessions → 401 ✅

## 清理
- DELETE tb_chat_message 中 1013 相关测试消息（保留 T1↔T2 互关演示消息 id 1,2,3,9）✅
- DEL Redis chat:limit:1:1013:20260807 ✅
