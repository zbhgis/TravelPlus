
# 客服异步多线程改造 - 重启验证报告

> 验证时间：2026-08-08
> 场景：用户1=T1（13686869696/123456, id=1）咨询客服（SUPPORT_USER_ID=1021）
> 后端：PID 38572，端口 8081，Spring Boot 3.5.9（`start-backend.bat` 重启）

## A. 重启后端

- ✅ 杀掉旧进程（23588=Spring Boot 应用 / 44584=Maven 父进程），端口 8081 释放
- ✅ `cmd /c start-backend.bat` 静默重启，新进程 PID 38572，日志确认 `Tomcat started on port 8081`
- ⚠️ 轮询说明：根路径 `/` 返回 404（Invoke-WebRequest 视为 not ready），实际服务已就绪；以日志 `Tomcat started` + 业务接口 200 为准
- ✅ 线程池日志确认：日志中出现 **`travelplus-support-1` / `travelplus-support-2`** 线程名（MyBatis 插入客服回复时的执行线程），线程池 `supportTaskExecutor`（core=2/max=4/queue=100/CallerRunsPolicy）生效

## B. 异步客服验证（用户1 T1）

### B1. 响应即时
- ✅ `POST /support/chat {"message":"异步测试问题"}` → **56ms**（curl -w time_total，远 <1s）
- ✅ 返回 `{"success":true,"data":"已收到你的问题，思考中..."}`（不再同步等待 AI）

### B2. 用户消息立即落库
- ✅ 基线 user→support=9，请求后**立即**查询 =10（+1）
- ✅ 日志佐证：`42:40.011 [nio-8081-exec-2] ChatMessageMapper.insert Parameters: 1(Long),1021(Long)`（请求线程同步写入）

### B3. AI 回复异步落库
- ✅ support→user 基线 4 → 请求后 =5（+1），新记录 id=65，content 长度 86，非空
- ✅ 日志佐证（异步关键证据）：
  - `42:40.011 [nio-8081-exec-2]` 用户消息落库（同步、即时）
  - `42:42.159 [lplus-support-1]` AI 回复落库（**约 2.1s 后由线程池线程写入**，异步）
  - 即：请求线程毫秒级返回，AI 调用 + 回复落库发生在 `travelplus-support-*` 后台线程

### B4. 多线程验证
- ✅ 连续 3 条消息（间隔 0.5s），全部**立即返回**：msg1=39ms、msg2=36ms、msg3=19ms（无排队阻塞）
- ✅ 8s 后落库核对：user→support=13（+3）、support→user=8（+3），AI 回复 id=69/70/71（content 长度 49/75/84，均非空）
- ✅ 日志佐证：AI 回复落库分布在多个线程
  - `43:17.626 [lplus-support-1]`、`43:17.778 [lplus-support-2]`（并行处理）、`43:19.718 [lplus-support-1]`
  - 两个不同线程 id 出现 → 线程池多线程并发处理确认

## 结论

✅ **客服异步多线程改造验证通过**：请求线程仅做"校验 + 用户消息落库 + 提交任务"毫秒级返回（56ms/39ms/36ms/19ms），AI 调用（1-3s）与回复落库全部在 `travelplus-support-` 前缀的线程池后台执行，用户消息与 AI 回复均正确落库 tb_chat_message（from/to 方向正确、内容非空），多线程并发无阻塞、消息不丢失。
