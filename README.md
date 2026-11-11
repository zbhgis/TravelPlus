# TravelPlus 旅游服务平台

TravelPlus 是一个前后端分离的旅游服务平台示例项目，核心能力覆盖用户登录、景点检索、游记互动、私信社交、门票秒杀下单、AI 客服与钱包积分体系。

## 功能概览

| 模块 | 主要功能 |
|------|----------|
| 用户 | 手机号验证码/密码登录注册、编辑资料、隐私设置、浏览记录、签到 |
| 内容 | 景点（详情/门票/动态评分/打卡/足迹/收藏/点赞）、游记（发布/编辑/瀑布流/评论/置顶） |
| 社交 | 关注/粉丝/获赞、关注 Feed、私信会话（置顶/删除/未读红点）、消息中心三分类通知 |
| 交易 | 门票秒杀（Lua 预检 + Kafka 异步落库 + 超时自动关单）、钱包三资产（余额/积分/秒杀券）、充值、积分抵扣 |
| AI | 企业级 Agent 双应用：旅游攻略助手（SSE 流式 + 工具调用 + 动态追问）与智能客服 Agent（SSE 流式 + 意图路由 + 16 篇知识库 RAG + 订单/积分/秒杀工具 + 人工工单工作台 + 👍/👎 反馈） |
| 旅行 | 高德地图（卫星/标准图层切换）、驾车/步行/公交三模式路线规划、多日旅行计划、本日路线自动规划 |
| 搜索 | Elasticsearch 全文搜索（景点/游记/用户三索引）、热门词、输入联想 |

## 项目亮点

- **秒杀高并发链路**：Redis Lua 脚本原子预检（防超卖 + 一人一单）→ 扣减库存 → 发 Kafka 异步落库 → Redisson 分布式锁 → DB 条件更新 → 定时任务超时自动关单并回补库存，请求立即返回订单 id，秒杀入口不阻塞。
- **缓存治理**：布隆过滤器防穿透（不存在的 id 直接拦截，不落缓存/DB）、Caffeine + Redis 多级缓存（并发回源只发生一次）、TTL 随机错峰防雪崩、主从 + 3 哨兵集群高可用（master 故障自动提升，Redisson 无缝重连）。
- **搜索优化**：Elasticsearch 三索引替代 MySQL LIKE 模糊查询，压测数据高命中场景提速约 5 倍、零命中场景约 20 倍；ES 不可用时自动降级为 LIKE 查询，保证功能可用。
- **企业级 Agent 架构**（对标 LangGraph/Spring AI 的生产模式）：共享一套 Agent 基础设施——注解式工具注册（`@AgentTool` 自动生成 function calling schema + 参数自愈校验 + 权限边界）、Redis 服务端会话记忆（多端续连）、三重预算护栏（轮数/工具次数/墙钟死线）、用户级限流（背压语义）、LLM 三态熔断、运行指标端点（`GET /ai/metrics`）；旅游攻略助手与智能客服 Agent 各自拥有独立编排器，复用同一套工具/记忆/熔断/预算治理能力。
- **智能客服 Agent**：SSE 流式（meta/delta/thinking/tool/replace/error/done(messageId)）→ 规则意图路由（HUMAN/COMPLAINT/ORDER/SECKILL/ACCOUNT/FAQ/OTHER，短追问继承上一轮意图）→ 双端护栏（提示词注入、验证码/密码套取、内部信息泄露、手机号脱敏）→ 知识库 RAG（16 篇 FAQ + 同义词扩展 + 外部 Markdown 热重载）→ 工具白名单（订单/订单详情/秒杀场次/积分签到/知识库/转人工）→ 人工工单状态机（WAITING/HUMAN 时 AI 静默）→ 输出护栏 → 落库并回传消息 id。客服工作台支持工单列表、完整会话转录、接入、回复、结束恢复 AI、差评反馈与知识库重载。
- **客服反馈闭环**：每条客服回复支持 👍/👎，点踩可填写原因；差评在工作台按「原因 + 原始回复」聚合展示，人工结束提示引导用户评价本次服务，形成「反馈 → 归因 → 优化知识库/提示词」闭环。
- **客服高峰并发方案**：当前执行池 core=2/max=4/queue=100（客服与旅行助手共用），适合小规模；高峰期加固方案（线程池隔离、AbortPolicy + SERVICE_BUSY、全局并发许可、单用户并发 1、FAQ 缓存、容量指标）见 [客服 Agent 高峰并发方案](docs/客服Agent高峰并发方案.md)。
- **旅游攻略助手体验**：SSE v2 结构化协议（meta/delta/tool/thinking/error/done，工具执行时间线 + 思考态动画）、回答长度按意图分层（普通 300 字 / 规划档 600~1000 字结构化长文）、回答完成后动态生成上下文追问 chips。
- **地图能力**：高德 JS API 全站接入（标记/路线/定位/图上选点），驾车（多途经点）/步行/公交三模式路线规划，全站地图统一支持卫星影像与标准底图切换。

## 目录结构

```
travelplus/
├── travelplus-backend/      # 后端（Spring Boot，端口 8081），含 src/、docker/、scripts/、docs/
├── travelplus-frontend/     # 前端（纯静态 H5 SPA，端口 19608）
├── docs/                    # 架构与容量方案（客服 Agent 高峰并发方案等）
├── docker-compose.yml       # 全局依赖编排：MySQL + Kafka + ES + Redis 集群 一键启动
├── README.md                # 项目根说明（本文件）
├── .gitignore               # 仓库级统一忽略规则
```

> - `travelplus-backend/`：Java 项目，含 `src/`、`docker/`、`scripts/`（秒杀/搜索压测脚本）、`docs/` 等，历史包含 2026-02 至今的完整提交链。
> - `travelplus-frontend/`：纯静态页面，含 `css/`、`js/`、`index.html`，通过 `python -m http.server` 提供访问，历史同样完整保留。

## 技术栈

- **后端**：Spring Boot 3.5.9 / JDK 24 / Maven 3.9.x
- **数据库**：MySQL 8.0（Docker 容器，端口 3307，root/123456，库名 travelplus）
- **缓存**：Redis 主从+哨兵集群（redis-master 6380 / redis-slave 6381 + 哨兵 26379/26380/26381，统一密码 123456）
- **缓存框架**：Redisson + Caffeine（本地缓存）
- **消息队列**：Kafka（Docker 容器，端口 9092，秒杀链路生产端使用）
- **搜索引擎**：Elasticsearch（索引为派生数据，后端启动时自动重建）
- **AI**：DeepSeek（OpenAI 兼容协议）、SSE 流式事件协议、注解式工具注册（`@AgentTool`）
- **前端**：原生 HTML/CSS/JS 单页应用（SPA）

## 快速开始

### 环境要求

- JDK 24 + Maven 3.9.x
- Docker（用于一键拉起 MySQL / Redis 集群 / Kafka / Elasticsearch）

### 启动步骤

1. **拉起依赖容器**（MySQL / Redis 集群 / Kafka / ES）：

   ```bash
   docker compose -f docker-compose.yml up -d
   ```

2. **启动后端**（端口 8081）：

   ```bash
   cd travelplus-backend
   mvn spring-boot:run
   ```

3. **启动前端**（端口 19608）：

   ```bash
   cd travelplus-frontend
   python -m http.server 19608
   ```

4. **访问**：浏览器打开 http://localhost:19608
5. **客服工作台**：登录后打开 http://localhost:19608/#/support-admin（默认 1 号用户为管理员；生产通过 `travelplus.support.admin-user-ids` 配置白名单）。

## 演示账号

- 手机号 `13912345678`，密码 `123456`（演示环境所有账号密码统一为 `123456`）
- 亦可直接注册新账号使用

## API 示例

**登录**（密码登录，返回 token 放入 `authorization` 请求头）：

```bash
curl -X POST http://localhost:8081/user/login \
  -H "Content-Type: application/json" \
  -d '{"phone":"13912345678","password":"123456"}'
```

**业务接口**（景点详情，需携带登录 token）：

```bash
curl http://localhost:8081/attraction/1 \
  -H "authorization: <登录返回的 token>"
```

**旅游 AI 助手**（SSE 流式，匿名可用；带 token 时可操作行程）：

```bash
curl -N -X POST http://localhost:8081/ai/chat \
  -H "Content-Type: application/json" \
  -H "authorization: <token>" \
  -d '{"message":"杭州一日游怎么安排？"}'
```

事件流示例：`{"meta":{"runId":"...","sessionId":"..."}}` → `{"tool":{"name":"search_notes","status":"done","ms":56}}` → `{"delta":"..."}` → `{"done":true}`。
追问建议：`curl -X POST http://localhost:8081/ai/followups -H "Content-Type: application/json" -d '{"sessionId":"<meta 返回的 sessionId>"}'`。

**智能客服 Agent**（SSE 流式，需登录；含工具时间线、护栏替换与落库 messageId）：

```bash
curl -N -X POST http://localhost:8081/support/chat/stream \
  -H "Content-Type: application/json" \
  -H "authorization: <token>" \
  -d '{"message":"帮我查一下我的订单"}'
```

事件流示例：`{"meta":{...}}` → `{"thinking":true}` → `{"tool":{"name":"get_my_orders","status":"running"}}` → `{"delta":"..."}` → `{"done":true,"messageId":165}`。

客服状态 / 转人工 / 恢复 AI / 反馈：`GET /support/status`、`POST /support/handoff`、`POST /support/resume`、`POST /support/feedback`；客服工作台接口在 `/support/admin/**`。

## 端口清单

| 服务 | 端口 | 说明 |
|------|------|------|
| 后端 | 8081 | Spring Boot（travelplus-backend） |
| 前端 | 19608 | 静态 H5 SPA（travelplus-frontend） |
| MySQL | 3307 | 主业务库（库名 travelplus） |
| Kafka | 9092 | 秒杀链路消息队列 |
| Elasticsearch | 9400 | 全文搜索 |
| Redis 主节点 | 6380 | 全部读写入口 |
| Redis 从节点 | 6381 | 实时复制，故障转移候选主 |
| Redis 哨兵 | 26379 / 26380 / 26381 | 高可用监控与自动故障转移 |

## 启动方式

### 第一步：一键启动全部依赖容器（MySQL / Redis 集群 / Kafka / Elasticsearch）

所有依赖统一由项目根目录 `docker-compose.yml` 编排，一条命令全部拉起：

```bash
# 在项目根目录执行
docker compose -f docker-compose.yml up -d
```

- 包含 8 个容器：`travelplus-mysql`（3307）、`travelplus-kafka`（9092）、`travelplus-es`（9400）、
  `redis-master`（6380）/ `redis-slave`（6381）+ 哨兵 `redis-sentinel1/2/3`（26379/26380/26381）。
- Redis 集群为**主从 + 哨兵高可用**架构：master 故障时哨兵自动将 slave 提升为新主，
  后端（Redisson）无需改配置即可重连。
- 数据持久化：MySQL / Kafka / Redis 均复用既有持久化卷（compose 内 `external: true` 引用），
  重建容器不会丢数据；ES 索引为派生数据，后端启动时自动从 MySQL 全量重建。
- 哨兵配置文件 `sentinel*.conf` 位于 `travelplus-backend/docker/redis-sentinel/`，
  由根 compose 挂载引用（原独立 Redis 编排已移除，统一由根编排管理）。
- 停止全部依赖：`docker compose -f docker-compose.yml stop`；移除容器（卷保留）：`down`。

### 第二步：启动后端（端口 8081）

```bash
cd travelplus-backend
mvn spring-boot:run
```

或双击 `travelplus-backend/start-backend.bat`（后台静默启动，日志在 `travelplus-backend/logs/backend.log`）。

### 第三步：启动前端（端口 19608）

```bash
cd travelplus-frontend
python -m http.server 19608
```

或双击 `travelplus-frontend/start-frontend.bat`（后台静默启动，日志在 `travelplus-frontend/logs/frontend.log`）。

### 停止服务

双击对应 `stop-*.bat` 即可（按端口查找进程并终止）。

## 环境变量

- `DEEPSEEK_API_KEY`：AI 客服与旅游攻略助手调用 DeepSeek API 所需密钥，**不在仓库中**。
  两种提供方式（优先级从高到低）：
  1. 环境变量 `DEEPSEEK_API_KEY`（生产环境推荐）；
  2. 本地开发在 `travelplus-backend/src/main/resources/application-local.yaml` 中覆盖
     （该文件已加入 `.gitignore`，仓库提供 `application-local.yaml.example` 模板，复制后填入自己的 key）。
  两者都未配置时留空，应用正常启动，AI 功能走兜底逻辑。
- 高德地图 Key：`travelplus-frontend/index.html` 内嵌 Web 端 JS Key——前端凭据本身随页面公开，
  属高德标准接入方式；请使用自己控制台申请的 Key 并配置域名白名单。
- `SUPPORT_KB_PATH`：可选，客服知识库外部 Markdown 路径（对应 `travelplus.support.knowledge-path`）。配置后可在客服工作台点「重载知识库」热更新；留空则使用 `classpath:knowledge/support-faq.md` 内置 16 篇 FAQ。

## Agent 架构速览（`com.travelplus.agent`）

```
POST /ai/chat ──► AgentController（薄 HTTP 层：输入护栏 → 会话解析 → 限流）
                    │
                    ▼
              AgentOrchestrator（有界状态循环：meta → 记忆 → 提示词+数据接地 → 工具循环 → 收敛）
                    │                    ├─ ToolRegistry        注解式工具注册 + schema 生成 + 权限边界
                    │                    ├─ ChatMemoryStore     Redis 服务端会话记忆（多端续连）
                    │                    ├─ PromptRegistry      提示词版本化（resources/prompts/*.md）
                    │                    ├─ ContextBuilder      平台数据接地（城市景点 + 游记 RAG）
                    │                    ├─ LlmCircuitBreaker   连续失败熔断 → 半开探测
                    │                    └─ AgentMetrics        运行指标（GET /ai/metrics）
                    ▼
        ┌───────────────────────────┬──────────────────────────────────────┐
   旅游攻略助手（SSE 流式）          智能客服 Agent（SSE 流式）
   工具：查行程/搜景点/搜游记/        意图路由 → 双端护栏 → 知识库 RAG → 工具白名单
   加行程/优化顺序/建计划             → 人工工单 → 输出护栏 → 反馈闭环
                                    SupportAgentOrchestrator 统一编排
```

- **SSE v2 协议**：`meta`（runId/sessionId）→ `thinking`（思考态）→ `tool`（工具时间线 running/done/failed + 耗时）→ `delta`（正文增量）→ `replace`（输出护栏修正全文）→ `error`（类型化错误码）→ `done`。客服的 `done` 额外携带落库 `messageId`，前端据此挂载 👍/👎。
- **动态追问**：回答完成后 `POST /ai/followups` 按会话记忆生成 3 个上下文相关的追问建议，前端快捷提问 chips 实时更新。
- **治理配置**：`travelplus.ai.agent.*`（轮数/工具次数/死线、限流、熔断、记忆 TTL）；`travelplus.support.*`（客服管理员白名单、客服限流、人工工单 TTL、反馈开关、外部知识库路径）。

### 客服 Agent 关键能力

| 能力 | 实现 |
|---|---|
| 意图路由 | 7 类意图（HUMAN/COMPLAINT/ORDER/SECKILL/ACCOUNT/FAQ/OTHER）；短追问无业务词时继承上一轮意图 |
| 双端护栏 | 输入拦截提示词注入、验证码/密码套取；输出拦截系统提示词/内部工具名泄露，手机号脱敏 |
| 知识库 RAG | 16 篇平台 FAQ，中文二元组 + IDF + 口语同义词（登陆/登录、退票/退款、抢票/秒杀）；支持外部 Markdown 热重载 |
| 工具白名单 | 按意图最小化：订单意图只给订单工具，账户意图只给资产工具；客服无法调用行程写操作 |
| 人工接管 | 工单状态机 0 待接入 → 1 人工中 → 2 已结束；WAITING/HUMAN 时 AI 静默；人工回复复用客服 1021 落私信表 |
| 客服工作台 | `#/support-admin`：工单列表、完整会话转录、接入、回复、结束恢复 AI、差评反馈、知识库重载 |
| 反馈闭环 | 每条回复 👍/👎 + 点踩原因；工作台差评聚合原因与原始回复；人工结束提示引导评价 |
| 可观测 | `/support/admin/metrics`：运行量、意图分布、转人工数、护栏拦截、兜底、反馈正负、待接入队列 |

## 高峰并发说明

客服 Agent 与旅行助手共用 `supportTaskExecutor`（core=2 / max=4 / queue=100 / CallerRunsPolicy）。该配置适合本地演示与小规模使用；真实高峰期建议按 [客服 Agent 高峰并发方案](docs/客服Agent高峰并发方案.md) 做线程池隔离、快速失败、全局并发许可、FAQ 缓存与容量指标。文档包含容量测算、目标流量反推、参数建议、压测场景与验收标准；产品侧（削峰/分流/兜底/运营）方案见 [客服高峰并发产品方案](docs/客服高峰并发产品方案.md)。

## 注意事项

- 演示环境密码统一为 `123456`（MySQL、Redis 等），仅作开源演示用途，生产环境请改为环境变量注入。
- 数据持久化：`docker compose down` 不会删除持久化卷（`external: true`），数据长期保留；
  如需彻底清理请显式执行 `docker volume rm`。
