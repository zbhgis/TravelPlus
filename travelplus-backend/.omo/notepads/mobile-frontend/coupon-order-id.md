# 秒杀券页：完整订单号展示

## 需求背景
用户反馈秒杀券上没有详细的订单号等相关的信息（券上订单号被截断成 `NO.****101`，无法完整看到/复制）。

## 改动（纯前端，未改后端）
文件：`travelplus-frontend/js/pages/coupons.js`、`travelplus-frontend/css/app.css`

1. **券卡显示完整订单号**
   - 删除 `truncateId()` 截断逻辑，券编号直接显示 `订单号：{item.id}`（完整 18 位，前端 id 为字符串无精度问题）。
   - `.coupon-card-id` 字号调小为 11px 并加 `word-break: break-all`，过长可换行、可复制。

2. **券卡信息补全**（保持左信息/右状态+箭头两栏布局）
   - 追加显示：`金额：¥x.xx`（`fenToYuan(item.ticketPrice)`，分→元）、`支付时间`（`formatDate(item.payTime)`，有值才显示）。

3. **条形码弹层补全详情**
   - `showBarcodeModal` 由 `(orderId, ticketName)` 改为接收完整 `item`（点击卡片按索引 `list[i]` 回传）。
   - 弹层信息区改为 label+value 信息行：完整订单号 / 票名 / 金额(¥) / 购买时间 / 支付时间(有值才显示) / 状态(statusText)，末尾保留有效期说明。
   - 条形码渲染（JsBarcode canvas + CSS 降级）逻辑未动。

4. **样式**（app.css 注释更新为【秒杀券详情的实现】）
   - 券编号完整显示样式（11px + 可换行）。
   - `.barcode-info` 改左对齐，新增 `.barcode-info-row`（label 灰 + value 黑，下划线分隔）、`.barcode-info-label`、`.barcode-info-value`。

## 验证
- `node --check js/pages/coupons.js` 通过。
- 手工/Playwright（375px，13912345678/123456）：`#/coupons` 未使用 tab 券卡显示完整订单号 `900000000000010101` + 金额 ¥0.01 + 购买时间；点击券 → 弹层显示完整订单号/票名/金额/购买时间/支付时间/状态。

## 注意
- 后端 `GET /ticket-order/list` 字段（id/ticketName/ticketPrice/status/statusText/createTime/payTime）已足够，无需改动。
- 金额单位为分，前端统一 `fenToYuan` 转元显示。