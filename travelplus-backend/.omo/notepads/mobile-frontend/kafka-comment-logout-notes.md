# Kafka 秒杀消费链路 / 评论 / 登出 改动摘要（mvn -q compile 通过）

## A. Kafka 秒杀订单消费链路（【kafka消息队列的实现】）
- `config/KafkaConfig.java`：启用 3 个 Bean 并修正 bug
  - import 修正：删除 Jackson 的 `com.fasterxml.jackson.databind.JsonDeserializer` / `StringDeserializer`，改用 Kafka 官方 `org.apache.kafka.common.serialization.StringDeserializer`
  - `consumerFactory()`：bootstrap-servers=localhost:9092、group-id=your-group-id（与 application.yaml 一致）；KEY/VALUE 均用 StringDeserializer（发送端发 JSON 字符串）
  - `errorHandler()`：FixedBackOff(interval=1000ms, maxAttempts=3)，耗尽回调 log.error 告警；可重试=SocketTimeoutException，不可重试=NullPointerException
  - `seckillTicketOrderKafkaListenerContainerFactory()`：AckMode 改为 **MANUAL_IMMEDIATE**（配合手动 ack.acknowledge()，原 RECORD 与手动 ack 冲突）
  - 每个 Bean/关键行加详细中文【kafka消息队列的实现】注释
- `consumer/SeckillTicketConsumer.java`：启用 `@KafkaListener(containerFactory="seckillTicketOrderKafkaListenerContainerFactory", topics="seckill-ticket-order")`
  - 解析改为 `JSONUtil.toBean(String.valueOf(record.value()), TicketOrder.class)`（原 `(TicketOrder) record.value()` 对 String 消息必 ClassCastException）
  - 签名 `ConsumerRecord<String, String>`（StringDeserializer 还原字符串）
  - 保留：落库成功后 `ack.acknowledge()`；异常不 ack、向上抛出交给 errorHandler 重试
- `service/impl/TicketOrderServiceImpl.java`：发送端 `kafkaTemplate.send("seckill-ticket-order", JSONUtil.toJsonStr(ticketOrder))`（原 toString() 无法被 JSONUtil 解析）；注释说明发送 JSON 字符串与 StringDeserializer+JSONUtil 配套、null 字段自动忽略
- `TravelPlusApplication.java`：已存在 `@EnableKafka`（无需新增），补充了启用原因注释

## B. 评论功能
- `entity/TravelNoteComment.java`：新增 `@TableField(exist=false)` 的 `icon`/`name`（风格同 TravelNote）
- `service/ITravelNoteCommentService.java`：新增 `Result saveComment(TravelNoteComment)`、`Result queryComments(Long noteId, Integer current)`
- `service/impl/TravelNoteCommentServiceImpl.java`：
  - saveComment：UserHolder 取 userId；校验 noteId/content 非空；parentId/answerId 默认 0、liked 默认 0、status 设 1（实体 Boolean→true）；save 后同步 `tb_travel_note.comments+1`（经 ITravelNoteService.update）
  - queryComments：createTime 升序分页（SystemConstants.MAX_PAGE_SIZE）；每条经 IUserService.getById 补 name/icon（取不到昵称给"匿名"）
- `controller/TravelNoteCommentController.java`：补 `POST /note-comments`（body TravelNoteComment）与 `GET /note-comments/of/{noteId}?current`（默认1）；需登录（不在拦截器 exclude 列表）

## C. 登出
- `controller/UserController.java`：注入 HttpServletRequest + StringRedisTemplate；logout 从 `authorization` 头取 token，非空删除 Redis `login:token:{token}`（LOGIN_USER_KEY），返回 Result.ok()；注释说明"删除 Redis 登录态，前端随后清理本地 token"

## 验证
- `mvn -q compile` 通过（仅 Maven 自身 JDK 告警，无代码编译错误）
- 未重启后端；未改前端/DB/秒杀 Lua；未加依赖；未 git 操作
