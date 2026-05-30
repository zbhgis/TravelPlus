# 钱包余额流水 / 模拟充值 / 积分抵扣支付 / 秒杀券状态过滤（Backend）实现与验证摘要

日期：2026-08-12
范围：TravelPlus 后端（Spring Boot 3.5.9 / MyBatis-Plus / MySQL docker travelplus-mysql:3307 / Redis），
钱包余额流水 + 模拟充值 + 订单支付积分抵扣 + 秒杀券列表状态过滤。

## 实现内容

- **DB 迁移（临时 .sql UTF-8 无 BOM → docker cp 进容器 → docker exec sh -c "mysql --default-character-set=utf8mb4 ... < file"，
  注意：PowerShell 5.1 管道会二次转码成 ?/双重 UTF-8，必须走 docker cp + 容器内 < 重定向 + 显式 utf8mb4 连接字符集）**：
  - 新表 tb_balance_record：id BIGINT UNSIGNED AUTO_INCREMENT PK、user_id BIGINT UNSIGNED NOT NULL、
    change_amount DECIMAL(10,2) NOT NULL（正=收入 负=支出）、type VARCHAR(20) NOT NULL
    （recharge=充值 pay=消费 refund=退款 admin=调整）、pay_method VARCHAR(10) NOT NULL DEFAULT ''
    （wechat=微信 ali=支付宝 balance=余额支付）、description VARCHAR(200) NOT NULL DEFAULT ''、
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP、INDEX idx_user(user_id)；
  - src/main/resources/db/travelplus.sql 同步：新增 tb_balance_record 建表（含【余额流水的实现】注释，
    不写业务数据，延续"运行时业务数据种子不写入"约定）。
- **实体/Mapper**：entity/BalanceRecord.java（@Data+@Accessors(chain)+@TableName，注释【余额流水的实现】）、
  mapper/BalanceRecordMapper.java（BaseMapper，@MapperScan("com.travelplus.mapper") 已覆盖，无需 @Mapper）。
- **余额流水 GET /user/balance/records?current=（UserController + UserServiceImpl.queryBalanceRecords，注释【余额流水的实现】）**：
  - 需登录（/user/** 拦截域）；userId 取 UserHolder；
  - balanceRecordMapper.selectPage 按 user_id=我 create_time 倒序分页（每页 MAX_PAGE_SIZE）→
    返回 List<Map{id, changeAmount(元, 直取 DB), type, typeText, payMethod, description, createTime}> + total；
  - typeText 映射：recharge=充值 pay=消费 refund=退款 admin=调整（static Map BALANCE_TYPE_TEXT）。
- **模拟充值 POST /user/recharge（body {amount:元, payMethod:'wechat'|'ali'}，Map 解析风格同 /chat/send，
  UserController + UserServiceImpl.recharge，注释【充值功能的实现】）**：
  - 校验 amount 非空且 >0 且 <=5000（元）→ fail"充值金额需大于0且不超过5000元"；payMethod 仅 wechat/ali → fail"不支持的支付方式"；
  - 模拟直接成功：update().setSql("balance = balance + amount") SQL 原子自增（防并发读改写覆盖），未命中 fail"用户不存在"；
  - INSERT tb_balance_record(+amount, type='recharge', pay_method=payMethod, description='模拟充值成功')；
  - 余额增加 + 流水记录同一 @Transactional 原子提交；返回 ok data=Map{balance=充值后余额}。
- **订单支付积分抵扣 + 写消费流水（TicketOrderServiceImpl.payOrder(Long orderId, Integer usePoints) 增强，
  注释【订单支付的实现】/【积分抵扣的实现】/【余额流水的实现】）**：
  - 校验顺序：①归属校验 → ②状态校验（仅 status=1 可支付）→ ③金额换算（pay_value 分→元 BigDecimal）→
    ③.5 积分抵扣：usePoints>0 时非 100 整数倍 fail"积分抵扣需为 100 的整数倍"、points<usePoints fail"积分不足"；
    抵扣金额=usePoints/100（BigDecimal.valueOf(usePoints,2)）；应付=max(原价-抵扣, 0.01)（允许抵扣但至少实付 0.01 元，避免 0 元订单）；
    → ④余额校验（balance<应付 fail"余额不足，请先充值"）→ ⑤原子扣款（UPDATE balance=balance-应付 WHERE id AND balance>=应付，
    affected=0 fail）→ ⑤.5 扣积分（UPDATE points=points-usePoints WHERE id AND points>=usePoints，affected=0 抛异常回滚）
    + INSERT tb_points_record(-usePoints, type='order', description='订单支付积分抵扣') → ⑥条件更新订单 status 1→2+pay_type=1+pay_time
    （affected=0 抛异常回滚扣款/扣积分）→ ⑥.5 INSERT tb_balance_record(-应付, type='pay', pay_method='balance',
    description='购买秒杀券：{票名}'）；全部同一 @Transactional 原子提交；
  - 返回 ok data=Map{remainingBalance, usedPoints}（无参/usePoints 缺省=0 不抵扣，保留无参兼容）。
- **秒杀券列表状态过滤（TicketOrderServiceImpl.queryOrderList(Integer current, Integer status) + Controller，
  注释【秒杀券状态过滤的实现】）**：
  - 增加可选 status 参数（缺省 null=查全部）；QueryWrapper.eq(status != null, "status", status) 条件拼接；
  - 前端秒杀券页 status=2（未使用=已支付）与 status=3（已核销）各调一次；statusText 映射不变。
- **接口签名变更**：ITicketOrderService.payOrder(Long,Integer)/queryOrderList(Integer,Integer)；
  TicketOrderController pay 增加 @RequestBody(required=false) Map body 解析 usePoints；list 增加 @RequestParam(required=false) status。
- 未动秒杀 Kafka 链路、超时取消、签到链路。

## 验证结果（用户 1010 / 13912345678，登录 token 放 authorization 头；中文在 GBK 控制台显示为 ?/乱码，
用 RawContentStream.ToArray() + UTF-8 解码 + HEX 核对真实内容）

- 基线：wallet={balance:470.50, points:158, couponCount:1}；tb_balance_record COUNT=0。
- **充值**：POST /user/recharge {amount:100, payMethod:'wechat'} → success data={balance:570.50}；
  DB tb_user.balance=570.50；tb_balance_record 出现 (user_id=1010, change_amount=100.00, type=recharge,
  pay_method=wechat, description=模拟充值成功)。
- **流水**：GET /user/balance/records → data=[{id, changeAmount:100.00, type:"recharge", typeText:"充值",
  payMethod:"wechat", description:"模拟充值成功", createTime}]，total=1。
- **积分抵扣支付**：INSERT 测试订单 900000000000000005（user_id=1010, ticket_id=21, status=1,
  create_time=DATE_ADD(NOW(),INTERVAL 9 HOUR)）；ticket21 pay_value=9900（99 元）；
  POST /ticket-order/pay/900000000000000005 body {usePoints:100} → success
  data={remainingBalance:472.50, usedPoints:100}；DB：balance 570.50→472.50（实付 98=99-1）、
  points 158→58、订单 status=2+pay_type=1+pay_time、tb_points_record 出现 (-100, type=order,
  description=订单支付积分抵扣)、tb_balance_record 出现 (-98.00, type=pay, pay_method=balance,
  description=购买秒杀券：千岛湖游船秒杀票)。
- **积分不足**：INSERT 测试订单 900000000000000006 后 POST pay {usePoints:500} → success=false
  errorMsg="积分不足"（HEX 核对）；订单仍 status=1、balance/points/流水不变。
- **非 100 倍数**：POST pay {usePoints:150} → success=false errorMsg="积分抵扣需为 100 的整数倍"；状态不变。
- **状态过滤**：GET /ticket-order/list?status=2 → 仅已支付 2 单（含既有 900000000000010101）；?status=3 → data=[] total=0；
  ?status=1 → 仅未支付 1 单；无参 → 3 单全量。
- **清理**：DELETE 测试订单 900000000000000005/900000000000000006、DELETE 测试 tb_balance_record（recharge+pay 两条）、
  DELETE 测试 tb_points_record(id=5, -100 order)、UPDATE tb_user SET balance=470.50, points=158；
  复验 wallet={balance:470.50, points:158, couponCount:1}、balance/records total=0、既有 sign 流水与已支付订单保留。
- 编译：mvn compile -q EXIT=0；重启后端（杀 8081 + travelplus java → start-backend.bat → 轮询 /attraction-type/list 200）。

## 关键经验

- PowerShell 5.1 管道把 UTF-8 字节按 GBK 二次编码（中文变 ? 或双重 UTF-8）；docker 执行含中文 SQL 必须
  docker cp 文件进容器 + docker exec sh -c "mysql --default-character-set=utf8mb4 ... < /tmp/file.sql"。
- update().setSql(...) 返回 boolean 而非 int（MyBatis-Plus LambdaUpdateChainWrapper），别用 int x = update()...update()。
- JSON 响应中文在 GBK 控制台显示乱码属显示问题：Invoke-WebRequest .RawContentStream.ToArray() 后
  [Text.Encoding]::UTF8.GetString() + 对 errorMsg/中文段做 HEX 比对确认真实内容。
- 金额一律 BigDecimal：pay_value 分→元用 BigDecimal.valueOf(x, 2)；抵扣 usePoints/100 同理；应付下限 0.01 元。
- 积分抵扣/余额扣款/扣积分/订单状态/双流水全部在一个 @Transactional，条件更新 affected=0 抛 RuntimeException 回滚。

## 前端对接提示

- 余额明细：GET /user/balance/records?current=N → {data:[{changeAmount(元, 正收入负支出), type, typeText,
  payMethod(wechat/ali/balance), description, createTime}], total}。
- 充值：POST /user/recharge body {amount: 元, payMethod: 'wechat'|'ali'} → ok data={balance: 充值后余额}；
  单次上限 5000 元；失败 errorMsg="充值金额需大于0且不超过5000元"/"不支持的支付方式"。
- 支付：POST /ticket-order/pay/{id} body 可选 {usePoints: 100 整数倍积分}（缺省=0 不抵扣）→
  ok data={remainingBalance, usedPoints}；100 积分抵 1 元，抵扣后至少实付 0.01 元；
  失败 errorMsg="积分不足"/"积分抵扣需为 100 的整数倍"/"余额不足，请先充值（当前余额：X 元）"等。
- 秒杀券页：GET /ticket-order/list?current=N&status=2（未使用=已支付）、&status=3（已核销）各调一次分页拉取；
  无 status=全量订单列表。