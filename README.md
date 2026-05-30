# TravelPlus 旅游服务平台

TravelPlus 是一个前后端分离的旅游服务平台示例项目，核心能力覆盖用户登录、景点检索、游记互动、私信社交、门票秒杀下单、AI 客服与钱包积分体系。

## 功能概览

| 模块 | 主要功能 |
|------|----------|
| 用户 | 手机号验证码/密码登录注册、编辑资料、隐私设置、浏览记录、签到 |
| 内容 | 景点（详情/门票/动态评分/打卡/足迹/收藏/点赞）、游记（发布/编辑/瀑布流/评论/置顶） |
| 社交 | 关注/粉丝/获赞、关注 Feed、私信会话（置顶/删除/未读红点）、消息中心三分类通知 |
| 交易 | 门票秒杀（Lua 预检 + Kafka 异步落库 + 超时自动关单）、钱包三资产（余额/积分/秒杀券）、充值、积分抵扣 |
| AI | DeepSeek 异步 AI 客服（线程池回复 + 前端轮询） |
| 旅行 | Leaflet 地图、附近景点、多日旅行计划 |
| 搜索 | Elasticsearch 全文搜索（景点/游记/用户三索引）、热门词、输入联想 |

## 项目亮点

- **秒杀高并发链路**：Redis Lua 脚本原子预检（防超卖 + 一人一单）→ 扣减库存 → 发 Kafka 异步落库 → Redisson 分布式锁 → DB 条件更新 → 定时任务超时自动关单并回补库存，请求立即返回订单 id，秒杀入口不阻塞。
- **缓存治理**：布隆过滤器防穿透（不存在的 id 直接拦截，不落缓存/DB）、Caffeine + Redis 多级缓存（并发回源只发生一次）、TTL 随机错峰防雪崩、主从 + 3 哨兵集群高可用（master 故障自动提升，Redisson 无缝重连）。
- **搜索优化**：Elasticsearch 三索引替代 MySQL LIKE 模糊查询，压测数据高命中场景提速约 5 倍、零命中场景约 20 倍；ES 不可用时自动降级为 LIKE 查询，保证功能可用。
- **异步 AI 客服**：DeepSeek 接口在线程池中异步回复，前端轮询拉取，不阻塞聊天交互。

## 目录结构

```
travelplus/
├── travelplus-backend/      # 后端（Spring Boot，端口 8081），含 src/、docker/、scripts/、docs/
├── travelplus-frontend/     # 前端（纯静态 H5 SPA，端口 19608）
├── docker-compose.yml       # 全局依赖编排：MySQL + Kafka + ES + Redis 集群 一键启动
├── README.md                # 项目根说明（本文件）
├── .gitignore               # 仓库级统一忽略规则
└── .omo/                    # 开发笔记（git 忽略，不入库）
```

> **monorepo 说明**：本项目已由前后端两个独立 git 仓库合并为单一仓库（本目录），双方提交历史均完整保留。
> - `travelplus-backend/`：Java 项目，含 `src/`、`docker/`、`scripts/`（秒杀/搜索压测脚本）、`docs/` 等，历史包含 2026-02 至今的完整提交链。
> - `travelplus-frontend/`：纯静态页面，含 `css/`、`js/`、`index.html`，通过 `python -m http.server` 提供访问，历史同样完整保留。

## 技术栈

- **后端**：Spring Boot 3.5.9 / JDK 24 / Maven 3.9.x
- **数据库**：MySQL 8.0（Docker 容器，端口 3307，root/123456，库名 travelplus）
- **缓存**：Redis 主从+哨兵集群（redis-master 6380 / redis-slave 6381 + 哨兵 26379/26380/26381，统一密码 123456）
- **缓存框架**：Redisson + Caffeine（本地缓存）
- **消息队列**：Kafka（Docker 容器，端口 9092，秒杀链路生产端使用）
- **搜索引擎**：Elasticsearch（索引为派生数据，后端启动时自动重建）
- **前端**：原生 HTML/CSS/JS 单页应用（SPA）

## 快速开始

### 环境要求

- JDK 24 + Maven 3.9.x
- Docker（用于一键拉起 MySQL / Redis 集群 / Kafka / Elasticsearch）
- Python 3（仅前端静态服务需要，也可用任意静态服务器替代）

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

- `DEEPSEEK_API_KEY`：AI 客服功能调用 DeepSeek API 所需密钥，运行时通过环境变量提供；
  未配置时默认空值，应用仍可正常启动，AI 客服走兜底逻辑。

## 注意事项

- 演示环境密码统一为 `123456`（MySQL、Redis 等），仅作开源演示用途，生产环境请改为环境变量注入。
- 数据持久化：`docker compose down` 不会删除持久化卷（`external: true`），数据长期保留；
  如需彻底清理请显式执行 `docker volume rm`。
- 本机 `6379` 端口是其他项目的 Redis（company-agent-redis），与 TravelPlus 无关，请勿混淆。
- 前后端已合并为单一 git 仓库（monorepo），根目录即仓库根；`travelplus-backend/` 与 `travelplus-frontend/` 各自保留独立的提交历史。

## License

本项目尚未指定开源许可证，默认保留所有权利（All Rights Reserved）。如用于学习与二次开发，请自行选择合适的开源许可证（如 MIT / Apache-2.0）并补充 `LICENSE` 文件。
