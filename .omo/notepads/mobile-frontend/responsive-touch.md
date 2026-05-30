# 移动端响应式 + 触屏模拟改造摘要

> 日期：2026-08-11 | 范围：`travelplus-frontend/css/app.css`

## 改造内容

### A. 触屏模拟（`app.css` 第 28-50 行，注释标签 `【触屏模拟的实现】`）

| 规则 | 实现 | 位置 |
|------|------|------|
| `-webkit-tap-highlight-color: transparent` | `html` 已存在，新增全局统一 | L28 |
| `touch-action: manipulation` | `html` 新增，消除双击缩放 300ms 延迟 | L28 |
| `user-select: none` | `body` 全局；`input/textarea/[contenteditable]` 例外保留选择 | L29, L31 |
| `-webkit-touch-callout: none` | `body` 新增，禁止 iOS 长按菜单 | L29 |
| `overscroll-behavior-y: contain` | `body` 新增，防止橡皮筋滚动穿透 | L29 |
| 滚动条手机化 | `body` 细滚动条 4px；`.chat-messages/.side-drawer/.attraction-search-dropdown` 3px；横向滚动容器 `.nearby-scroll/.dest-cards-scroll/.dest-tabs-wrap` 延续隐藏 | L33-40 |
| `-webkit-overflow-scrolling: touch` | `body` + 所有滚动容器统一添加 | L29, L37 |
| 按压反馈 `:active` | `.card/.attraction-card/.note-card/.ticket-card` → `scale(0.98)`<br>`.tabbar-item` → `scale(0.92)`<br>`.channel-tab/.tab-item/.login-tab/.fav-tab/.user-tab/.search-result-tab` → 背景加深<br>`.btn` 基类 → `opacity: 0.82`<br>`.feed-note-card` → `scale(0.985)`<br>`.trip-template-card/.buddy-card` → 背景加深<br>`.comment-item` → 背景加深<br>`.dest-tab` → `opacity: 0.75` | L42-50 |
| 触屏目标尺寸 | `.tabbar-item` min-height 44px；`.channel-tab` min-height 40px；其余 tab 36px | L3584-3587 |

### B. 响应式适配（`app.css` 末尾，注释标签 `【响应式适配的实现】`）

| 断点 | 内容 |
|------|------|
| `@media (max-width: 360px)` | 缩减各处 padding 16→12px；attraction-card 图片 120→100px；登录表单/注册表单 max-width 100%；头像预览 200→150px；轮播 overflow:hidden 兜底；瀑布流间距收紧；msg-categories/overview-cards/dest-cards/nearby-cards 间距调整；ticket-card 字号微调；共覆盖 40+ 选择器 | L3476-3538 |
| `@media (min-width: 768px)` | body/html 背景加深为灰色（#e8eaed / #d5d7db）；app-container 添加 `border-radius:12px` + `box-shadow` 手机框效果 + `margin:20px auto` 垂直居中；global-search-bar 顶部圆角；tabbar 底部圆角；桌面滚动条加粗至 6px/4px | L3541-3570 |
| 溢出修复（全局） | `.app-container { overflow-x: hidden }`；`.img-carousel { overflow: hidden; max-width:100% }`；`.carousel-track { max-width:100% }`；`.login-form/.register-form/.login-tabs { max-width:100% }`；`.modal-card/.avatar-preview-card { max-width:90vw }`；`.notes-waterfall { overflow-x: hidden }` | L3572-3587 |

### C. JS 语法检查

```
node --check js/main.js  → 通过
node --check js/router.js → 通过
```

### D. Playwright 跨宽度实测

| 宽度 | 测试路由 | overflow | 备注 |
|------|----------|----------|------|
| 320px | `#/login` | 无 | docW=305 / winW=320 |
| 320px | `#/home` | 无 | docW=305 / winW=320 |
| 320px | `#/notes` (瀑布流) | 无 | docW=305 / winW=320 |
| 320px | `#/search` | 无 | docW=320 / winW=320 |
| 320px | `#/travel` | 无 | docW=305 / winW=320 |
| 768px | `#/home` | 无 | 桌面手机框效果生效 |
| 768px | `#/login` | 无 | docW=753 / winW=768 |
| 768px | `#/notes` | 无 | docW=753 / winW=768 |

触屏 CSS 属性实测：
- `touch-action: manipulation` ✓
- `user-select: none` (body) ✓
- `-webkit-user-select: none` ✓
- `overscroll-behavior-y: contain` ✓
- `-webkit-tap-highlight-color: transparent` ✓

桌面 (768px) 样式实测：
- app-container `border-radius: 12px` ✓
- global-search-bar `border-radius: 12px 12px 0 0` ✓
- tabbar `border-radius: 0 0 12px 12px` ✓
- body `background: rgb(213,215,219)` ✓

小屏 (320px) 样式实测：
- `page-content` padding `10px 12px`（@media max-width:360px 生效）✓
- app-container `border-radius: 0`（手机模式无圆角框）✓
- docW ≤ winW，零横向溢出 ✓

## 未改动

- 所有 JS 逻辑（`js/pages/*.js`、`js/main.js`、`js/router.js` 等）
- 后端代码
- 无 git 操作
- 无新增依赖