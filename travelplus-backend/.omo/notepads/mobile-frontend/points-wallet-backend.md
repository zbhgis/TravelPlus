# 签到积分 / 钱包资产 / 订单管理 / 积分记录（Backend）实现与验证摘要

日期：2026-08-11
范围：TravelPlus 后端（Spring Boot 3.5.9 / MyBatis-Plus / Redis BitMap），签到积分 + 钱包 + 订单管理 + 积分流水。

## 实现内容

- **DB 迁移（临时脚本已执行进 docker MySQL travelplus 库，并用 cmd `<` 重定向避免 PowerShell 5.1 吞编码）**：
  - `tb_user` 新增 `points INT NOT NULL DEFAULT 0`（积分余额）、`balance DECIMAL(10,2) NOT NULL DEFAULT 0.00`（钱包余额·元）；
  - 新表 `tb_points_record`：id BIGINT UNSIGNED AUTO_INCREMENT PK、user_id、change_points INT（正=增 负=扣）、
    type VARCHAR(20)（sign/order/refund/admin）、description VARCHAR(200) DEFAULT ''、create_time、INDEX idx_user(user_id)；
  - 演示数据：测试账号 1010（13912345678）预置 points=100、balance=50.00（可留可改）。
  - `src/main/resources/db/travelplus.sql` 同步：tb_user 建表加两列；16 条 positional INSERT 追加 `, 0, 0.00`
    （1010 行 = `, 100, 50.00`，加注释说明演示数据）；新增 tb_points_record 建表（不写业务数据）。
- **实体/Mapper**：`entity/PointsRecord.java`（@Data+@TableName）、`mapper/PointsRecordMapper.java`（BaseMapper）、
  `entity/User.java` 新增 `points`(Integer)、`balance`(BigDecimal) 字段。
- **签到修复 + 积分（UserServiceImpl.sign 重写，注释【签到积分的实现】）**：
  - 修复重复签到 bug：SETBIT 前先 `getBit(key, dayOfMonth-1)` 检查，已签直接 `fail"今天已签到，明天再来吧"`（原幂等 SETBIT 静默成功，前端"明明签到过还显示可签到"）；
  - 未签才 SETBIT → `update().setSql("points = points + 2").eq("id", userId).update()` SQL 原子自增（防并发读改写覆盖）→
    INSERT tb_points_record(+2, type='sign', description='每日签到奖励')；流水失败 try/catch 记日志不阻断签到。
  - signCount/signList 仍读同一 BitMap（sign:{userId}:yyyyMM），未改动。
- **订单管理 `GET /ticket-order/list?current=`（TicketOrderController + ITicketOrderService.queryOrderList，注释【订单管理的实现】）**：
  - 需登录（/ticket-order/** 在 LoginInterceptor 拦截域）；userId 取 UserHolder；
  - page() 按 user_id=我 倒序分页（每页 MAX_PAGE_SIZE=10）→ 一次 selectBatchIds 批量 join tb_ticket 取 title/pay_value；
  - 返回 List<Map{id, ticketId, ticketName, ticketPrice(分, 直取 pay_value), status, statusText, payType, createTime, payTime}> + total；
  - statusText 映射：1未支付 2已支付 3已核销 4已取消 5退款中 6已退款（static LinkedHashMap ORDER_STATUS_TEXT）。
- **钱包资产 `GET /user/wallet`（UserController + UserServiceImpl.queryWallet，注释【钱包资产的实现】）**：
  - 查 tb_user 的 balance（元）/points；couponCount = COUNT(tb_ticket_order WHERE user_id=我 AND status IN (2,3))；
  - data=Map{balance, points, couponCount}。
- **积分记录 `GET /user/points/records?current=`（UserController + UserServiceImpl.queryPointsRecords，注释【积分记录的实现】）**：
  - selectPage 按 user_id=我 倒序分页查 tb_points_record；data=List<Map{id, changePoints, type, typeText, description, createTime}> + total；
  - typeText 映射：sign=签到奖励 order=下单抵扣 refund=退款返还 admin=运营调整（static Map POINTS_TYPE_TEXT）。
- 未动秒杀 Kafka 链路（TicketOrderServiceImpl.seckillTicket/createTicketOrder 与 SeckillTicketConsumer 未改，仅新增 queryOrderList）。

## 验证结果（用户 1010，token 放 authorization 头，curl -o 文件避免 GBK 控制台吞中文）

- 登录 `POST /user/login`（13912345678/123456）→ token 正常；JSON 入参用 `--data-binary @file`（PS 5.1 会吞内嵌双引号）。
- `GET /user/wallet` → {"success":true,"data":{"balance":50.00,"points":100,"couponCount":0}}（1010 预置值）。
- `POST /user/sign`（首次）→ success；wallet.points=102（+2）；DB：tb_user.points=102，tb_points_record 出现 (user_id=1010, change_points=2, type=sign, description=每日签到奖励)。
- `POST /user/sign`（重复）→ **success=false "今天已签到，明天再来吧"**（修复生效）；points 仍 102、sign 流水仍 1 条（未重复计分）。
- `GET /user/points/records?current=1` → data=[{id:1, changePoints:2, type:"sign", typeText:"签到奖励", description:"每日签到奖励", ...}]，total=1。
- `GET /ticket-order/list?current=1`（1010 无订单）→ data=[] total=0；
  - 临时造单 `INSERT tb_ticket_order (id=622874050099675999, user_id=1010, ticket_id=10, status=2)` 后 →
    data=[{id:622874050099675999, ticketId:10, ticketName:"西湖游船秒杀票", ticketPrice:1, status:2, statusText:"已支付", payType:1, ...}]，total=1；wallet.couponCount=1；
  - **测试后已 DELETE 该测试订单**，list 恢复 data=[] total=0，couponCount 恢复 0。
- `GET /user/sign/list` → data=[11]（今天的日期号在数组中，位图与 sign 写读一致）。
- 编译：`mvn compile -q` EXIT=0；重启后端（杀 8081 + travelplus java → start-backend.bat → 轮询 /attraction-type/list 200）。

## 关键经验

- PowerShell 5.1 调 curl 传 JSON 必须用临时文件 `--data-binary @file`；`docker exec -i mysql < file.sql` 不可用，用 `cmd /c "... < file.sql"`。
- 中文在 GBK 控制台显示为 `?`，属显示问题；用 UTF-8 无 BOM 文件 + 文件读取核对真实内容。
- 签到积分用 SQL 原子自增而非读-改-写，避免并发覆盖；位图先 SETBIT 再记流水，流水失败只记日志（可接受的最终一致缺口）。
- `/user/wallet`、`/user/points/records` 与已有 `@GetMapping("/{id}")` 无冲突（字面路径优先于路径变量）。

## 前端对接提示

- 钱包页：`GET /user/wallet` → {balance(元, 两位小数), points, couponCount(已支付/已核销秒杀券数)}。
- 签到：`POST /user/sign` 成功才发积分+2；失败 errorMsg="今天已签到，明天再来吧"（前端可据此置灰今日签到按钮）。
- 积分明细：`GET /user/points/records?current=N` → {data:[{changePoints(正增负扣), type, typeText, description, createTime}], total}。
- 订单管理页：`GET /ticket-order/list?current=N` → {data:[{ticketName, ticketPrice(单位:分), statusText, payType, createTime, payTime}], total}。
