# TravelPlus 旅游服务平台

TravelPlus 是一个前后端分离的旅游服务平台示例项目，核心能力覆盖用户登录、景点检索、游记互动、私信社交、门票秒杀下单、AI 客服与钱包积分体系。

## 目录结构

```
travelplus/
├── travelplus-backend/      # 后端（Spring Boot，端口 8081，独立 git 仓库）
├── travelplus-frontend/     # 前端（纯静态 H5 SPA，端口 19608，独立 git 仓库）
└── README.md                # 项目根说明（本文件）
```

- 后端：`travelplus-backend/`，Java 项目，含 `src/`、`docker/`、`scripts/`、`docs/` 等。
- 前端：`travelplus-frontend/`，纯静态页面，含 `css/`、`js/`、`index.html`，通过 `python -m http.server` 提供访问。

## 技术栈

- **后端**：Spring Boot 3.5.9 / JDK 24 / Maven 3.9.x
- **数据库**：MySQL 8.0（Docker 容器，端口 3307，root/123456，库名 travelplus）
- **缓存**：Redis 主从+哨兵集群（redis-master 6380 / redis-slave 6381 + 哨兵 26379/26380/26381，统一密码 123456）
- **缓存框架**：Redisson + Caffeine（本地缓存）
- **消息队列**：Kafka（Docker 容器，端口 9092，秒杀链路生产端使用）
- **搜索引擎**：Elasticsearch
- **前端**：原生 HTML/CSS/JS 单页应用（SPA）

## 启动方式

依赖容器（MySQL / Redis 集群 / Kafka / Elasticsearch）均通过 Docker Compose 编排，配置见 `travelplus-backend/docker/`。

### 启动后端（端口 8081）

```bash
cd travelplus-backend
mvn spring-boot:run
```

或双击 `travelplus-backend/start-backend.bat`（后台静默启动，日志在 `travelplus-backend/logs/backend.log`）。

### 启动前端（端口 19608）

```bash
cd travelplus-frontend
python -m http.server 19608
```

或双击 `travelplus-frontend/start-frontend.bat`（后台静默启动，日志在 `travelplus-frontend/logs/frontend.log`）。

### 停止服务

双击对应 `stop-*.bat` 即可（按端口查找进程并终止）。

## 环境变量

- `DEEPSEEK_API_KEY`：AI 客服功能调用 DeepSeek API 所需密钥，需在启动后端前配置。

## 注意事项

- 演示环境密码统一为 `123456`（MySQL、Redis 等）。
- 本机 `6379` 端口是其他项目的 Redis（company-agent-redis），与 TravelPlus 无关，请勿混淆。
- 前后端为独立 git 仓库，各自维护提交历史。
