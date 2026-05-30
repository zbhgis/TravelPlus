# 订单管理页补充完整订单号 + 支付方式文本化（orders-detail-full）

日期：2026-08-12
范围：`travelplus-frontend/js/pages/orders.js` + `travelplus-frontend/css/app.css`

## 背景
用户反馈订单管理页（#/orders）也需要像秒杀券页（#/coupons）一样显示**详细订单信息**，尤其是**完整订单号**。原订单卡只显示票名+状态+金额+支付方式（数字）+时间，无订单号。

## 改动

### orders.js
1. 新增公共渲染函数 `orderCardHtml(item)`：首屏渲染与分页追加共用，避免两处重复。
   - 订单卡增加 `订单号：{item.id}` 行（`.order-card-id`，完整 18 位字符串）
2. 新增 `payTypeText(payType)` 映射：1→"余额支付"、2→"支付宝"、3→"微信支付"，默认"余额支付"。
   - 支付方式由原来显示数字改为显示中文文本
3. 分页追加改为 `orderCardHtml(item)` 生成 innerHTML 后取 firstElementChild 插入哨兵前（原为手工创建 DOM 元素）。
4. 保持不动：点击卡片→#/order/:id 详情（data-order-id + contentEl 事件委托）、statusClass 数字映射、fenToYuan、分页 loadMore、空态/失败态。

### app.css
- 新增 `.order-card-id`：`font-size:11px; color:var(--text-hint); font-family:monospace; word-break:break-all`（与秒杀券 `.coupon-card-id` 同一标准，完整订单号可换行不截断）。

## 验证（Playwright 375px，登录 13912345678/123456）
#/orders 订单卡实际显示：
- 订单号：900000000000010101（完整 18 位，未截断）
- 票名：西湖游船秒杀票
- 金额：¥0.01
- 状态：已支付（done 绿色徽标）
- 支付方式：余额支付（payType=1 文本化）
- 下单时间/支付时间：2026-08-01 10:00:00
- 点击订单卡 → 正常进入 #/order/900000000000010101 订单详情页（订单详情 banner 已支付、门票名称、金额、时间均正常）
- `node --check js/pages/orders.js` 通过

## 备注
- 不改后端，字段直接用后端 `GET /ticket-order/list` 已返回的 `id/ticketName/ticketPrice/status/statusText/payType/createTime/payTime`
- 无 git 操作
