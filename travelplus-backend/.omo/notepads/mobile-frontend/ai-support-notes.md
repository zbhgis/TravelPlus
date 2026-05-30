# AI 客服（langchain4j + DeepSeek）实现笔记

> 追加于 2026-08-07。实现侧事实与验证结果，供 orchestrator E2E 参考。

## 依赖（pom.xml）
- 坐标 **`dev.langchain4j:langchain4j-open-ai:1.18.1`**（注意：**groupId 是 `dev.langchain4j`，不是 `org.langchain4j`**——后者的 group 在 Maven 中央仓库不存在，任务描述里的坐标有误，已按真实坐标实现）。
- 1.18.1 为 2026-07 发布的当前最新稳定版，`mvn dependency:get` 经阿里云镜像实测解析成功。
- `langchain4j-core` 由 `langchain4j-open-ai` compile 级传递引入，无需单独声明。
- 传递依赖会升级 jackson 到 2.22.1（langchain4j-open-ai 显式依赖），`mvn -q compile` 通过，无冲突。
- Maven 不在 PATH：实际命令 `C:\Users\lenovo2026\tools\apache-maven-3.9.11\bin\mvn.cmd`。

## 配置（application.yaml）
```yaml
travelplus:
  cache:
    ticket-list: ...  # 原样保留
  ai:
    api-key: ${DEEPSEEK_API_KEY:}  # 开源脱敏：实际密钥经环境变量注入，不写入仓库
    base-url: https://api.deepseek.com
    model: deepseek-v4-flash
```
- `AiProperties`（config/AiProperties.java）`@ConfigurationProperties(prefix="travelplus.ai")`，字段 apiKey/baseUrl/model。

## 新增代码（全部带【AI客服的实现】注释）
- `service/ISupportService`：`Result chat(String message, Long userId)`
- `service/impl/SupportServiceImpl`：构造时 `OpenAiChatModel.builder().baseUrl(...).apiKey(...).modelName(...).temperature(0.7).build()`；
  固定 SYSTEM_PROMPT 覆盖平台功能（登录注册/景点/游记/秒杀/订单/签到/关注/浏览记录/消息通知）；
  单轮无状态（可扩展多轮）；任何异常 catch → log.error → `Result.fail("客服暂时不可用，请稍后再试")`，不暴露底层错误。
- `controller/SupportController`：`POST /support/chat`，body `{message}`，从 UserHolder 取 userId 透传。
- `dto/ChatDTO`：仅 `message` 字段。
- 鉴权：`/support/**` 不在 MvcConfig 的 excludePathPatterns 中 → 需登录（未登录 401），客服入口在个人主页，符合预期。

## 验证结果
- `mvn -q compile` 通过（EXIT=0），5 个新 class 全部产出。
- **真实 API 冒烟（2026-08-07，PowerShell 直接调 DeepSeek）**：
  - `POST https://api.deepseek.com/chat/completions`，Authorization Bearer + 密钥 → **HTTP 200**，密钥有效。
  - 模型 `deepseek-v4-flash` 有效，`temperature=0.7` 被接受。
  - **关键事实：`deepseek-v4-flash` 是推理模型**，响应含 `reasoning_content`（先输出推理），`content` 在其后产出。
    - `max_tokens` 过小（<=50）时 token 全耗在推理上 → `content` 为空 + `finish_reason=length`。
    - `max_tokens=200` 时 `content` 正常返回（finish_reason=stop），langchain4j `aiMessage().text()` 取 content 即可。
    - 结论：正常使用没问题，但若前端发现偶发空回复，优先怀疑 max_tokens/推理 token 占用（当前未显式设 maxTokens，走 langchain4j 默认，够用）。
- 中文乱码仅为 PowerShell 控制台输出编码问题（请求体按 UTF-8 bytes 发送、响应为 UTF-8），Java 应用无此问题。

## 给 orchestrator 的 E2E 提示
- 调用示例：`POST http://localhost:8081/support/chat`，头 `authorization: <登录token>`，body `{"message":"怎么秒杀门票"}`。
- 成功：`{success:true, data:"<客服回复>"}`；AI 调用失败/超时：`{success:false, errorMsg:"客服暂时不可用，请稍后再试"}`（不抛 HTTP 错误）。
- 后端需重启后生效（本次未重启后端）。
- DeepSeek 从本机可达（网络 OK）。