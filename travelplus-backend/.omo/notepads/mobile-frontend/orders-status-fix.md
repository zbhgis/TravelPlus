# 订单管理页"一直加载失败"修复 + 状态徽标完善

## 根因
`travelplus-frontend/js/pages/orders.js` 的 `statusClass(status)` 对后端返回的**数字 status**（1未支付/2已支付/3已核销/4已取消/5退款中/6已退款）调用 `.toLowerCase()`：
```js
const s = (status || '').toLowerCase();  // Number 无 toLowerCase → TypeError
```
异常被 `loadOrders` 的 catch 吞掉 → 整个列表渲染失败 → 显示"加载失败"。此前空列表不进入渲染分支，故一直未暴露。

## 改动
1. `orders.js` `statusClass()` 重写为数字归一映射（不做 toLowerCase）：
   - 2/3 → `done`（绿色）
   - 1 → `pending`（橙色）
   - 4/5/6 → `cancel`（灰色）
   - 其他（含 NaN）→ `''` 默认灰
2. `orders.js` 首屏与 load-more 两处渲染：status===1 时追加
   `<div class="order-pay-tip">请在 15 分钟内完成付款，超时订单自动取消并回补库存</div>`
3. `app.css` 新增 `.order-pay-tip`（12px / #b45309 浅橙小字）。`.order-status.done/.pending/.cancel` 三态色原本已存在，未改。

## 验证（Playwright 375px，登录 13686869696/123456 密码模式）
- #/orders 不再"加载失败"；订单卡片正常渲染：乌镇西栅日游秒杀票 ¥129.00 + 状态"未支付"（`.order-status.pending`，bg #fef3c7 / 文字 #d97706）+ 付款提示（12px #b45309）
- 无 console error / pageerror
- `node --check orders.js` 通过

## 踩坑记录
- 验证时浏览器命中 Python http.server 的**旧缓存 main.js**（无 /orders 路由）→ 显示"页面不存在"。需 CDP `Network.clearBrowserCache` + `setCacheDisabled` 后重载。后续改动 JS 后验证必须先清缓存。