# 消息页三分类圈未读角标修复记录

## 2026-08-12：进入消息页不再自动全部已读

**缺陷**：进入 `#/messages` 后三分类圈未读角标（likes/follows/comments）用户看不到。
**根因**：`js/pages/messages.js` `renderMessages` 中，页面渲染完成后立即执行 `if (unreadCount > 0) { put('/notify/read'); setMessagesBadge(false); ...badge.remove() }` —— 进入页面即全读 + 移除角标，角标刚渲染就被清掉。

**修复**（仅改前端 `travelplus-frontend/js/pages/messages.js`，后端接口未动）：

1. 删除 `renderMessages` 中进入页面即 `put('/notify/read')` + 清空 `.msg-cat-badge` 的逻辑（微信/小红书语义：消息中心展示各分类未读角标，点进分类详情页才已读）。
2. 三圈角标由 `renderCategoryCircles(catUnread)` 渲染后保持显示；用户点击分类圈 → `#/messages/likes` 等详情页 → 详情页内已有 `put('/notify/read')` 全读逻辑（保留）→ 返回消息页 hash 回退重新渲染 → 重新拉取 `category-unread-count` 自然归零 → 角标消失。
3. 进入消息页不再强制 `setMessagesBadge(false)`（tabbar 红点保持到真正已读）；5s 轮询 `refreshMessagesBadge()` 保留，随已读状态自动更新。
4. 顺带移除已不再使用的 `unreadCount` 变量与其 `GET /notify/unread-count` 调用（tabbar 红点由 router 的 refreshBadge 独立轮询，不受影响）。

**验证**（Playwright 375px，登录 13912345678/123456）：

- SQL `UPDATE tb_notify SET is_read=0 WHERE user_id=1010` 制造未读（like 21 + favorite 1 = likes 22，follow 5，comment 4，共 31 条）。
- 进入 `#/messages`：角标显示 `9+ / 5 / 4`，等待 800ms 后仍存在（不再被立即清除）。
- tabbar 红点：进入消息页时仍为可见（block）。
- 点击「赞和收藏」→ `#/messages/likes` 详情页正常（标题「赞和收藏」，加载 10 条）→ 返回 `#/messages` → 角标全部消失（已全读）。
- 再次进入 `#/messages`：无角标（全已读状态）。
- DB 校验：`tb_notify WHERE user_id=1010` 全部 `is_read=1`（31 条）。
- `node --check js/pages/messages.js` 通过。

**遗留说明**：测试中 1010 用户未读被真实消费（is_read 全部置 1），属正常用户行为，最终保持已读状态即可；如需恢复原样可再 `UPDATE tb_notify SET is_read=0 WHERE user_id=1010`。