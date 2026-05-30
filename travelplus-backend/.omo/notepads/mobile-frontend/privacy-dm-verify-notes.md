# 私信限制升级 + 隐私扩展 + 笔记可见性编辑 验证笔记（privacy-dm-verify）

> 验证日期：2026-08-08 | 方式：后端重启 + curl 全链路 | 结论：全部 ✅

## 环境
- 后端：localhost:8081（start-backend.bat 重启，日志 Started TravelPlusApplication）
- MySQL：travelplus-mysql:3307，库 travelplus；Redis：company-agent-redis:6379
- 测试账号：T1=1（13686869696/123456）、T2=2（13838411438/123456）、1013（13712340001/123456）

## 前置状态调整
- 初始 tb_follow 中 1013→1 存在（互关），为匹配「1 与 1013 非互关」测试前提，先 DELETE 1013→1，验证完毕恢复 INSERT。
- 清空 1↔1013 既有私信（当时为 0 条）与 Redis chat:stranger:* 计数 key。
- GET /chat/mutual-status 确认：peerId=1013 → mutual=false；peerId=2 → mutual=true。

## 私信限制（T1=1，T2=2，1013）
- ① T1 发第 1 条给 1013（非互关未回复）→ success data=17 ✅（首条放行）
- ② T1 再发第 2 条给 1013 → fail errorMsg="对方尚未回复你，今天最多发送一条消息" ✅
- ③ 1013 账号回复 T1（POST /chat/send toUserId=1）→ success data=18 ✅
- ④ T1 再发第 2 条给 1013 → success data=19（回复解锁） ✅
- ⑤ UPDATE tb_user_info SET dm_mode=1 WHERE user_id=1013 → T1 发 → fail "对方仅允许互关好友私信" ✅
- ⑥ UPDATE dm_mode=2 → T1 发 → fail "对方已关闭私信功能"；恢复 dm_mode=0 ✅
- ⑦ 互关（T1→T2）连续发 4 条 → 全 success data=20/21/22/23（不限） ✅

## 隐私校验（likes_visible）
- UPDATE tb_user_info SET likes_visible=0 WHERE user_id=1013：
  - T1 查 GET /user/liked-notes/1013 → fail "对方设置了隐私，该内容不可见" ✅
  - 本人 1013 查自己 /user/liked-notes/1013 → success data=8 条 ✅
- 恢复 likes_visible=1 ✅

## 笔记可见性（T1 建私有笔记 id=28）
- ① T1 POST /note {title:"私有测试",content:"x",images:...,visible:1} → success data=28 ✅
  （注：images 非空字段，缺省会 DB 报 "Field 'images' doesn't have a default value"，需带 images）
- ② T2 查 GET /note/28 → fail "该笔记仅作者可见"；T1 查 → success，visible=1 ✅
- ③ T1 PUT /note/28 {title:"私有测试改",visible:0} → success；T2 再查 → success，visible=0（已公开） ✅
- ④ T1 PUT /note/28 {title:"私有测试标题更新验证"} → success；GET 回显 title 匹配 ✅
- ⑤ 非作者 T2 PUT /note/28 → fail "无权编辑该游记" ✅
- ⑥ 清理：T1 DELETE /note/28 → success；再查 → fail "游记不存在" ✅

## 清理与恢复
- 恢复 tb_follow：INSERT 1013→1（回到初始互关态，1↔1013 双向存在）
- 删除 1↔1013 测试私信（剩余 0 条）；保留 T1→T2 示例消息（data 20-23）
- DEL Redis chat:stranger:1:1013:20260808
- 未做 git 操作；后端保持运行