# 超时任务时区修复 + 1 积分抵扣（2026-08-12）

## Bug ①：未支付订单被超时任务误杀（时区 8 小时偏差）

- **根因**：JDBC serverTimezone=UTC（DB 会话时区 UTC，tb_ticket_order.create_time 存 UTC 时间），而 JVM 为 UTC+8。OrderTimeoutTask.scanExpiredOrders 原用应用侧 LocalDateTime.now().minusMinutes(15) 计算 cutoff，与 DB 的 UTC create_time 比较时差 8 小时 → 新订单被立即判超时取消（status=4），用户支付时报"订单已取消"。
- **修复**：弃用应用侧 cutoff，改在 SQL 内比较 create_time < DATE_SUB(NOW(), INTERVAL PAY_TIMEOUT_MINUTES MINUTE)（MyBatis-Plus .apply() 拼接常量，无注入风险）。NOW() 与 create_time 同源（均 DB 会话时区），语义正确且不依赖部署环境时区。
- **文件**：src/main/java/com/travelplus/task/OrderTimeoutTask.java（移除 LocalDateTime import，更新类/方法 Javadoc）。
- cancelExpiredOrder 无时间比较，未改动。

## Bug ②：积分抵扣去掉 100 整数倍限制（1 积分可抵）

- **改动**：TicketOrderServiceImpl.payOrder 中删除 usePoints % 100 != 0 校验；usePoints=null→0 不抵扣；负数 fail"积分抵扣不能为负"；任意正整数均可。
- 抵扣金额 = BigDecimal.valueOf(usePoints).divide(100, 2, HALF_UP)（1 积分 = 0.01 元）；应付 = max(原价 - 抵扣, 0.01)，积分按 usePoints 全额扣。
- **顺序调整**：积分扣减在余额扣款之前（条件更新 affected=0 → fail"积分不足"）；余额扣款条件更新 affected=0 → 抛异常靠 @Transactional 回滚积分扣减。返回 data 增加 deductAmount。
- **文件**：TicketOrderServiceImpl.java、ITicketOrderService.java、TicketOrderController.java（Javadoc/注释同步）。

## 实测结果（登录 13912345678/123456 = 1010，baseline balance 470.50 / points 158）

1. **时区修复核心验证**：INSERT 订单 900000000000000007（create_time=NOW()）→ 等 75s 过扫描周期 → **status 仍为 1**（修复前会立即被取消）✅
2. **真超时仍生效**：INSERT 订单 900000000000000008（create_time=16 分钟前）→ 下个扫描周期 → **status=4** + DB stock 21 50→51 + Redis stock 50→51 ✅
3. **1 积分抵扣**：pay 007 body {"usePoints":1} → success，remainingBalance=371.51（470.50-98.99），usedPoints=1，deductAmount=0.01；订单 status=2，points 158→157，积分流水 -1，余额流水 -98.99 ✅
4. **积分不足**：造订单 009，pay {"usePoints":9999} → fail"积分不足"，无任何副作用 ✅
5. **清理完成**：删除 007/008/009、测试流水（points id>1、balance id>4）、恢复 balance=470.50 / points=158、DB stock 21=50、Redis stock 21=50、seckill:order:21 空集（与测试前一致）✅

## 备注

- 未改 JDBC 时区配置（改 serverTimezone 风险大）；未动 Kafka 秒杀链路；未改前端。
- pay_time 仍为应用侧 LocalDateTime.now() 写入（实测 12:20:45，create_time 为 UTC 04:17:51）——显示口径遗留问题，本次不在范围，前端展示时注意。
