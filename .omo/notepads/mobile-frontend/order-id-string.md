# 订单 id JSON Long 精度丢失修复（雪花 Long → String 序列化）

## 现象
订单 id 为 17 位雪花 Long（如 900000000000010101），超过 JS Number 安全整数上限 2^53≈9007199254740991，
前端 `res.json()` 解析数字后末尾精度丢失（实测 101→100），导致订单列表点击导航到错误订单。

## 根因
订单列表/详情接口返回 `Map{id, ticketId, ...}` 时直接 put 原 Long 值，Jackson 序列化为 JSON 数字；
前端 JS 解析后 Long 精度丢失。

## 修复
只改 `src/main/java/com/travelplus/service/impl/TicketOrderServiceImpl.java` 两处返回 Map 的组装处，
**未动全局 Jackson Long→String 配置**（避免影响其他接口/实体）：

- `queryOrderList()`（订单列表）：`item.put("id", String.valueOf(order.getId()))`、
  `item.put("ticketId", String.valueOf(order.getTicketId()))`
- `queryOrderDetail()`（订单详情）：`detail.put("id", ...)` / `detail.put("ticketId", ...)` 同转 String
- 注释同步补充【订单ID序列化的实现】：雪花 Long 序列化为字符串避免前端精度丢失；
  Map 中其他雪花 Long id（如 ticket 相关 id）若加入同样应转 String

## 验证
- `mvn compile` EXIT=0
- 重启后登录 13912345678/123456，`GET /ticket-order/list?current=1` 原始 JSON：
  `"id":"900000000000010101"`（带引号=字符串，非数字）
- `GET /ticket-order/900000000000010101` 详情同验：`"id":"900000000000010101"`
- node `JSON.parse` 模拟前端解析：`id === "900000000000010101"` 精确匹配，无精度丢失

## 注意
- 前端订单列表点击跳 `#/order/{id}` 用字符串 id，URL 参数无精度问题
- 后续在返回 Map 中新增任何雪花 Long id 字段，必须用 `String.valueOf()` 转 String
