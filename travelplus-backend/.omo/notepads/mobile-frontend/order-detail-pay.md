# 订单详情 + 订单余额支付（Backend）实现与验证摘要

日期：2026-08-12
范围：TravelPlus 后端（Spring Boot 3.5.9 / MyBatis-Plus / MySQL docker travelplus-mysql:3307），新增订单详情与钱包余额支付两个接口。

## 实现内容

### 订单详情 `GET /ticket-order/{id}`（注释【订单详情的实现】）

- **接口**：`TicketOrderController.queryOrderDetail`（`@GetMapping("/{id}")`）→ `ITicketOrderService.queryOrderDetail` → `TicketOrderServiceImpl.queryOrderDetail`。
- **路由冲突**：`@GetMapping("/{id}")` 与已有 `@GetMapping("/list")` 无冲突——Spring MVC 精确匹配优先，`/list` 命中字面路径，`/{id}` 只承接其余单段路径（已实测 `/list` 仍正常）。
- **归属校验**：`getOne(WHERE id=orderId AND user_id=当前用户)`，非本人订单查不到 → 统一 `fail"订单不存在"`，不区分"不存在/非本人"避免泄露他人订单号（已实测他人 token 查我订单返回"订单不存在"）。
- **join 门票**：`ticketMapper.selectById` 取 `title/sub_title/pay_value(分)/rules`；**join 景点（增强）**：经 `ticket.attraction_id` 查 `tb_attraction` 取景点名。
- **返回**：`data=Map{id, ticketId, ticketName, ticketPrice(分), status, statusText, payType, createTime, payTime, useTime, refundTime, rules, attractionName}`；statusText 复用 ORDER_STATUS_TEXT 映射（1未支付 2已支付 3已核销 4已取消 5退款中 6已退款）。
- **注入**：`@Resource AttractionMapper attractionMapper`（新注入，mapper 直注入避免 Service 循环依赖）。

### 订单支付 `POST /ticket-order/pay/{id}`（注释【订单支付的实现】）

- **接口**：`TicketOrderController.payOrder`（`@PostMapping("pay/{id}")`）→ `ITicketOrderService.payOrder` → `TicketOrderServiceImpl.payOrder`（`@Transactional`）。
- **步骤（顺序即正确性关键）**：
  1. **归属校验**：`getOne(WHERE id AND user_id=当前用户)`，查不到/非本人 → `fail"订单不存在"`；
  2. **状态校验**：仅 `status=1`（未支付）可支付；`2→"订单已支付"`、`4→"订单已取消，无法支付"`（含被 OrderTimeoutTask 超时取消的）、`3→"订单已核销"`、`5→"订单退款中"`、`6→"订单已退款"`——与超时任务条件更新（status 1→4）配套防竞态；
  3. **金额换算**：`tb_ticket.pay_value`（分）→ `BigDecimal.valueOf(payValue, 2)` 换算元，全程 BigDecimal 避免浮点误差；
  4. **余额校验**：`tb_user.balance < 应付` → `fail"余额不足，请先充值（当前余额：X 元）"`；
  5. **原子扣款**：`UPDATE tb_user SET balance=balance-应付 WHERE id=我 AND balance>=应付`（条件更新防并发超扣，affected=0 → `fail"余额不足，请先充值"`，本次未扣款无需回滚）；
  6. **状态更新**：`UPDATE tb_ticket_order SET status=2, pay_time=NOW(), pay_type=1 WHERE id=? AND status=1`（条件更新防与超时/重复支付竞态；affected=0 时余额已扣，**抛 RuntimeException 使 @Transactional 自动回滚扣款**，保证"扣款与订单状态"原子）；
  7. 成功返回 `ok`，`data=Map{remainingBalance=剩余余额(元)}`。
- **注入**：`@Resource UserMapper userMapper`（新注入，钱包余额校验/原子扣款）。

## 验证结果（用户 1010，token 放 authorization 头；响应写 `.omo/test-out/*.json` 后再 UTF-8 读取避免 GBK 控制台吞中文）

- 登录 `POST /user/login`（13912345678/123456）→ 用户 1010，**测试前 balance=470.50 元**；第二用户 13686869696 登录用于归属校验。
- **详情**：`GET /ticket-order/900000000000010101`（1010 真实已支付订单）→ `success:true`，data 完整：ticketName="西湖游船秒杀票"、ticketPrice=1、status=2、statusText="已支付"、payType=1、createTime/payTime=2026-08-01T10:00:00、rules="每人限购1张"、attractionName="西湖风景名胜区"。
- **成功支付**：INSERT 测试订单 900000000000000002（user_id=1010, ticket_id=21[9900分=99.00元], status=1）→ `POST pay` → `success:true, remainingBalance=371.50`；DB：订单 status=2、pay_type=1、pay_time 有值、**balance 470.50→371.50（−99.00 精确）**。
- **余额不足**：INSERT 测试订单 900000000000000003（ticket_id=22[12900分=129.00元]），临时把 balance 置为 100.00 → `POST pay` → `success:false "余额不足，请先充值（当前余额：100.00 元）"`；DB：balance 仍 100.00（未扣）、订单仍 status=1、pay_time 为 NULL；随后恢复 balance=371.50。
- **重复支付**：对已支付订单 900000000000000002 再 `POST pay` → `success:false "订单已支付"`。
- **归属校验**：用户 13686869696 的 token 支付/查询 1010 的订单 → 均 `success:false "订单不存在"`（支付与详情双接口均校验）。
- **清理**：删除两条测试订单；恢复 balance=470.50；真实订单 900000000000010101 status=2 未动。

## 踩坑（环境预置，非本次改动引入）

- **MySQL docker 容器时区为 UTC（NOW()=01:41），JVM 为 UTC+8（09:41）差 8 小时**：OrderTimeoutTask 用应用侧 `LocalDateTime.now().minusMinutes(15)` 作 cutoff 扫 `create_time`，DB 落库的 create_time 为 UTC → 新未支付订单会被超时任务在 60s 内取消（status 1→4，并回补 seckill 库存+Redis 库存）。
  - 现象：首次 INSERT 测试订单（create_time=NOW()）后立即支付返回 `fail"订单已取消，无法支付"`——**恰好验证了支付接口的防竞态逻辑正确拦截被超时取消的订单**。
  - 规避（测试用）：测试订单 `create_time=DATE_ADD(NOW(), INTERVAL 9 HOUR)` 使其相对应用 cutoff 落在未来，不被超时扫描命中。
  - 副作用已还原：被误取消的测试订单导致 tb_seckill_ticket(21).stock 51→50、Redis seckill:stock:21 51→50 均已 DECR 还原。
  - ⚠️ 该时区错位意味着**生产口径下真实秒杀订单也会在 ~8 小时+15 分钟前被提前取消**——若后续要修，方向是统一 DB/JVM 时区（如 JDBC `connectionTimeZone` 或建表 `DEFAULT CURRENT_TIMESTAMP` 改为应用写入），本次按"不破坏超时任务"约束未改动。
- PowerShell 5.1：`Invoke-WebRequest.Content` 对无 charset 的 JSON 按 Latin-1 解码会乱码，改用 `[Text.Encoding]::UTF8.GetString($resp.RawContentStream.ToArray())` 后写文件再 UTF-8 读取。

## 交付物

- `ITicketOrderService.java`：新增 `queryOrderDetail(Long)`、`payOrder(Long)`（含 Javadoc）。
- `TicketOrderServiceImpl.java`：新增两方法（各带【订单详情的实现】【订单支付的实现】注释）；注入 UserMapper、AttractionMapper。
- `TicketOrderController.java`：新增 `GET /ticket-order/{id}`、`POST /ticket-order/pay/{id}`。
- `mvn.cmd compile -q` EXIT=0；实测 6 项全过；测试数据已清理、balance/库存/真实订单状态均已还原。
