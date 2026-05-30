# 客服 AI 调用异步多线程化改动摘要（mvn -q compile 通过 EXIT=0，未重启后端，未 git 操作）

> 追加于 2026-08-08。将 SupportServiceImpl.chat 的同步 DeepSeek 调用改为异步多线程。

## A. 线程池 + 启用异步（【客服异步多线程的实现】）
- `TravelPlusApplication`：加 `@EnableAsync`（注释说明——开启 Spring 异步支持，配合 AsyncConfig 的线程池使用）
- 新建 `config/AsyncConfig.java`：`@Bean("supportTaskExecutor") ThreadPoolTaskExecutor`
  - corePoolSize=2 / maxPoolSize=4 / queueCapacity=100 / threadNamePrefix="travelplus-support-"
  - 拒绝策略 `CallerRunsPolicy`（线程满+队列满时由提交方 Tomcat 线程直接执行，保证消息不丢、前端轮询必收敛）
  - `waitForTasksToCompleteOnShutdown=true`（应用关闭时等待已提交任务完成，避免回复落库被中断）
  - 参数依据注释：AI 调用是 IO 密集（等待网络响应 1-3s，CPU 空闲），2-4 线程足够，避免占满 Tomcat 请求线程

## B. chat 异步化（【客服异步多线程的实现】）
- `SupportServiceImpl`：
  - 注入 `@Qualifier("supportTaskExecutor") ThreadPoolTaskExecutor supportExecutor`（构造器注入）
  - `chat(message, userId)` 改造：
    1. 校验 message 非空（空 → Result.fail("消息内容不能为空")，不落库不调模型）
    2. **同步** `persistUserMessage` 写用户消息（from=当前用户→客服 1021，is_read=1）——前端立即可见自己发的消息
    3. `supportExecutor.execute(() -> generateAndPersistReply(userId, message))` 提交**异步任务**
    4. 立即 `return Result.ok("已收到你的问题，思考中...")`（不再等待 AI，请求线程毫秒级返回）
  - 私有 `generateAndPersistReply`（线程池线程执行）：
    - 调 `chatModel.chat(messages)` → 成功 `persistAiReply` 写 AI 回复（from=客服→用户，is_read=0）
    - 失败（网络/模型/异常/空回复）→ `log.error`（不打印密钥）+ 写兜底回复 `"（客服暂时无法回复，请稍后再试）"` 到 tb_chat_message —— 保证前端轮询 /chat/list 必能等到一条回复而收敛
    - 不抛异常（线程池内异常需自行捕获，否则回复既不落库也不留日志）
  - 原 `persistConversation` 拆为 `persistUserMessage`（同步）+ `persistAiReply`（异步）
- `ChatController` 无改动（接口不变，返回语义变化：立即"思考中"）

## C. 契约（供前端对接）
- POST /support/chat 提交后：成功返回 `{success:true, data:"已收到你的问题，思考中..."}`（不再返回 AI 回复文本）
- AI 回复（或失败兜底回复）由后台线程写入 tb_chat_message，前端轮询 `GET /chat/list?peerId=1021` 获取
- 失败兜底回复 is_read=0，与正常 AI 回复形态一致，前端无需区分；轮询必然收敛（不无限等待）

## D. 验证
- `mvn -q compile` → EXITCODE=0（仅 JDK sun.misc.Unsafe 自身告警，无代码错误；无新增依赖）
- 未重启后端（orchestrator 统一）；未 git 操作；未改前端

## 改动文件
- config/AsyncConfig.java（新）
- TravelPlusApplication.java（+@EnableAsync）
- service/impl/SupportServiceImpl.java（chat 异步化 + 线程池注入 + persistConversation 拆分）