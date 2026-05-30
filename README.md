# TravelPlus 旅游服务平台

TravelPlus 是一个前后端分离的旅游服务平台示例项目，核心能力覆盖用户登录、景点检索、游记互动、私信社交、门票秒杀下单、AI 客服与钱包积分体系。

## 目录结构

```
travelplus/
├── travelplus-backend/      # 后端（Spring Boot，端口 8081）
├── travelplus-frontend/     # 前端（纯静态 H5 SPA，端口 19608）
├── docker-compose.yml       # 全局依赖编排：MySQL + Kafka + ES + Redis 集群 一键启动
├── README.md                # 项目根说明（本文件）
└── .gitignore               # 仓库级统一忽略规则
```

> **monorepo 说明**：本项目已由前后端两个独立 git 仓库合并为单一仓库（本目录），双方提交历史均完整保留。
> - `travelplus-backend/`：Java 项目，含 `src/`、`docker/`、`scripts/`、`docs/` 等，历史包含 2026-02 至今的完整提交链。
> - `travelplus-frontend/`：纯静态页面，含 `css/`、`js/`、`index.html`，通过 `python -m http.server` 提供访问，历史同样完整保留。

## 技术栈

- **后端**：Spring Boot 3.5.9 / JDK 24 / Maven 3.9.x
- **数据库**：MySQL 8.0（Docker 容器，端口 3307，root/123456，库名 travelplus）
- **缓存**：Redis 主从+哨兵集群（redis-master 6380 / redis-slave 6381 + 哨兵 26379/26380/26381，统一密码 123456）
- **缓存框架**：Redisson + Caffeine（本地缓存）
- **消息队列**：Kafka（Docker 容器，端口 9092，秒杀链路生产端使用）
- **搜索引擎**：Elasticsearch
- **前端**：原生 HTML/CSS/JS 单页应用（SPA）

## 启动方式

### 第一步：一键启动全部依赖容器（MySQL / Redis 集群 / Kafka / Elasticsearch）

所有依赖统一由项目根目录 `docker-compose.yml` 编排，一条命令全部拉起：

```bash
# 在项目根目录执行
docker compose -f docker-compose.yml up -d
```

- 包含 8 个容器：`travelplus-mysql`（3307）、`travelplus-kafka`（9092）、`travelplus-es`（9400）、
  `redis-master`（6380）/ `redis-slave`（6381）+ 哨兵 `redis-sentinel1/2/3`（26379/26380/26381）。
- 数据持久化：MySQL / Kafka / Redis 均复用既有持久化卷（compose 内 `external: true` 引用），
  重建容器不会丢数据；ES 索引为派生数据，后端启动时自动从 MySQL 全量重建。
- 停止全部依赖：`docker compose -f docker-compose.yml stop`；移除容器（卷保留）：`down`。
- 历史说明：`travelplus-backend/docker/redis-sentinel/docker-compose.yml` 为 Redis 集群
  独立编排，现已被全局 compose 涵盖并保持兼容（哨兵配置文件仍复用其中 `sentinel*.conf`）。

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

- `DEEPSEEK_API_KEY`：AI 客服功能调用 DeepSeek API 所需密钥，需在启动后端前配置。

## 注意事项

- 演示环境密码统一为 `123456`（MySQL、Redis 等），仅作开源演示用途，生产环境请改为环境变量注入。
- 数据持久化：`docker compose down` 不会删除持久化卷（`external: true`），数据长期保留；
  如需彻底清理请显式执行 `docker volume rm`。
- 本机 `6379` 端口是其他项目的 Redis（company-agent-redis），与 TravelPlus 无关，请勿混淆。
- 前后端已合并为单一 git 仓库（monorepo），根目录即仓库根；`travelplus-backend/` 与 `travelplus-frontend/` 各自保留独立的提交历史。
