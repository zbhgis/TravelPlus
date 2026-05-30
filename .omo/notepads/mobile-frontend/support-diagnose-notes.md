
## [2026-08-08] AI 客服罢工诊断（support/chat）

### 结论：AI 客服当前工作正常，未发现罢工根因

### 诊断过程与结果
1. 登录 T1（13686869696/123456）→ POST /user/login → success，token=fc62c440...
   - 注意：JSON body 用 PowerShell Set-Content(UTF8 带 BOM) 写文件再 -d @file 会污染 JSON 导致登录失败/解析错误；改用无 BOM 文件或 --data-raw 正常。
2. POST /support/chat {"message":"你好"}（带 token）→ success，data=AI 正常客服回复。
   - 后端日志同步显示双向落库 tb_chat_message（用户1↔客服1021，55:44），证明 DeepSeek 调用成功。
3. 直测 DeepSeek API（绕过应用）：model=deepseek-v4-flash + 配置密钥 → HTTP 200，正常返回。
   - 密钥有效、模型名有效、网络可达（响应 0.85s）。
4. application.yaml 配置核对：travelplus.ai api-key/base-url(model=deepseek-v4-flash) 与实际可用端点一致，无配置错误。
5. logs/backend.log 检索 "AI 客服调用失败 / DeepSeek / langchain4j / 401/429"：0 条失败记录。

### 修复建议
- 无需修复应用配置；若再现"客服罢工"表象，优先怀疑客户端请求 JSON 编码问题（BOM）或网络侧（应用服务器到 api.deepseek.com 出口）。
