# 客服异步回复"一直不响应"诊断（2026-08-08 13:05 完成）

## 现象
用户发"不响应测试"（user_id=1 → 客服1021）后，前端一直显示"思考中…"，30 秒后超时提示"客服响应超时，请稍后重试"。

## 结论：根因 = 前端字段名不匹配（senderId vs fromUserId），后端链路正常

### 后端链路：完全正常（证据链）
- 用户消息已落库：tb_chat_message id=81（from=1 → to=1021，"不响应测试"，13:01:44）。
- 异步线程已执行：backend.log 13:01:45 `travelplus-support-2` 线程执行（selectById 用户/客服 → insert 回复）。
- AI 回复已生成并落库：id=82（from=1021 → to=1），13:01:45，距用户消息约 1 秒。
- 无任何异常：日志中搜不到 "AI 客服异步调用失败" / "返回空回复" / DeepSeek / consume 相关 Exception，兜底 FALLBACK_REPLY 未触发。
- 线程池（travelplus-support-*）正常：85 处日志，任务提交、执行、落库全链路无阻塞。

### 根因：前端 support.js 用 `r.senderId` 判断 AI 回复，后端返回字段是 `fromUserId`
- 后端 `ChatServiceImpl.list`（GET /chat/list）返回 `List<ChatMessage>`，字段为 `fromUserId`（实体 ChatMessage.java:44）。
- 前端 `support.js` 全部用 `r.senderId`（L134/144/281/295）判断 `senderId === CUSTOMER_ID(1021)`：
  - `senderId` 恒为 `undefined` → `String(undefined) === "1021"` 恒 false → `currentAssistantCount` 恒 0。
  - 轮询检测条件 `currentAssistantCount > assistantCountBefore`（L290）恒不成立 → 永远检测不到新回复。
  - 30 秒后触发超时兜底（L333）：toast"客服响应超时"→ 表现为"一直不响应"。
- 对照：前端 `chat.js` L88/139 正确使用 `msg.fromUserId`，说明该项目其他页面字段是对的，仅 support.js 写错。

### 次要发现：DeepSeek 回复内容存在"模型拒答"文本（与兜底文案不同）
- 本次 id=82 实际回复为"抱歉，我现在暂时无法响应，请稍后再试。如有其他问题，随时告诉我！"。
- 该文案 ≠ 代码 FALLBACK_REPLY（"（客服暂时无法回复，请稍后再试）"，SupportServiceImpl.java:81），且日志无兜底触发 → 是 **DeepSeek 模型自身对"不响应测试"这类含歧义输入偶尔返回的拒答文本**（推理模型行为，偶发性）。
- 直测 DeepSeek：同一输入连测 5 次均正常（HTTP 200，1.7~3.9s，finish_reason=stop），仅 max_tokens 设很小（100）时 content 为空（reasoning 占满 token）→ 会触发代码空回复兜底。当前代码未设 maxTokens（langchain4j 默认），生产路径可正常出文本，但推理模型空回复风险真实存在。

## 修复建议
1. 【必须】前端 `travelplus-frontend/js/pages/support.js` 把 4 处 `r.senderId` / `msg.senderId` 改为 `r.fromUserId` / `msg.fromUserId`（L134/144/281/295，及 L119/120/143/265 注释）。
2. 【可选/加固】后端 `SupportServiceImpl` 给 OpenAiChatModel 显式设置较大 maxTokens（如 1024+），避免推理模型 reasoning 占满 token 导致 content 为空触发兜底文案。
3. 【可选】对模型返回"拒答文本"（含"无法响应"等）可考虑二次重试，但非本次问题根因，优先级低。

## 验证方式
- 改完后端无需重启（本次为纯前端改动）；后端无改动。
- 前端刷新 #/support 重新发消息，应 2 秒轮询内收到 AI 回复气泡，不再 30 秒超时。