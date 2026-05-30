# 消息页三分类圈未读提示（分类未读数角标）

> 日期：2026-08-12 · 需求：消息页顶部三分类圈（赞和收藏/新增关注/评论和@）显示各自未读数红点/数字角标

## 结论
已实现并全链路实测通过：后端新增按分类统计未读接口，前端三分类圈渲染未读角标（1-9 显示数字、≥10 显示 9+，≥100 显示 99+），进入消息页标记全部已读后角标随之后端 is_read 置 1 而清空。

## 后端（travelplus）
- `INotifyService`：新增 `Result categoryUnreadCount()`（注释标记【分类未读的实现】）。
- `NotifyServiceImpl.categoryUnreadCount()`：三次 count，均限定 `user_id=当前登录用户 + is_read=0`，分类映射与 `queryNotifyList` 严格一致：
  - likes → `type IN ('like','favorite')`
  - follows → `type IN ('follow')`
  - comments → `type IN ('comment','reply','mention')`
  - count null 兜底 0；返回 `LinkedHashMap{likes, follows, comments}`（保持三分类顺序）。
- `NotifyController`：新增 `GET /notify/category-unread-count`（需登录）。

## 前端（travelplus-frontend）
- `js/pages/messages.js`：
  - `renderCategoryCircles(catUnread)`：三圈结构内按 `data-cat` 输出 `.msg-cat-badge` 角标（未读 0 不输出）；`formatBadgeText()` 封顶 9+/99+，≥10 时加 `.msg-cat-badge-lg` 类。
  - `renderMessages`：`Promise.all` 三路并行（unread-count + chat/sessions + category-unread-count，第三路 `.catch` 兜底全 0）。
  - 已读联动：`PUT /notify/read` 成功 → `setMessagesBadge(false)` + 移除三分类角标；离开再进入重新拉取。
- `css/app.css`：`.msg-cat-item` 加 `position:relative`；新增 `.msg-cat-badge`（绝对定位圆形图标右上角 top:-2px/right:-2px，红色 --danger 背景白字 16px 圆点）与 `.msg-cat-badge-lg`（≥10 胶囊形）；`@media (max-width:360px)` 极小屏微缩角标。

## 实测记录
- 登录 13912345678/123456（用户 id=1010）。
- SQL 制造未读（like×2/follow×1/comment×3，is_read=0）→ `GET /notify/category-unread-count` 返回 `{"likes":2,"follows":1,"comments":3}`，与 `SELECT type, COUNT(*) ... GROUP BY type` 完全一致。
- Playwright 375px：#/messages 三圈角标显示 2/1/3；标记已读后角标消失、DB `is_read=0` 归零；like×22 时角标显示 "9+"（.msg-cat-badge-lg）。测试数据已恢复（is_read=1）。
- `mvn compile -q` EXIT=0；`node --check messages.js` 通过。

## 注意事项 / 踩坑
- 测试时若用 Playwright route 拦截 `PUT /notify/read`（abort）会让浏览器报 `net::ERR_FAILED` 控制台错误——那是测试注入，正常流程无错误。
- 同 URL 跳转 `goto('#/messages')` 可能不重新挂载页面（badge 不刷新），测试需先跳 `#/home` 再进 `#/messages`。