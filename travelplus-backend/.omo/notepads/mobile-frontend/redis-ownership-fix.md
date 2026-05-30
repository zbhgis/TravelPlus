# Redis 6379 归属修正（redis-ownership-fix）

> 日期：2026-08-12　类型：文档口径修正

## 修正背景

用户澄清：**6379（company-agent-redis）是其他项目的 Redis**，不是 TravelPlus 的。TravelPlus 仅曾以它为数据迁移源（384 keys 迁入集群），迁移后已完全脱离，应用配置指向哨兵集群。

## 正确口径（现行事实）

- TravelPlus Redis = 主从 + 3 哨兵：redis-master(6380) / redis-slave(6381) + redis-sentinel1/2/3(26379/26380/26381)，统一密码 123456
- 配置：`docker/redis-sentinel/docker-compose.yml`；Spring 指向 `spring.data.redis.sentinel.master=mymaster, nodes=127.0.0.1:26379,26380,26381, password=123456`
- 6379（company-agent-redis）：其他项目 Redis，仅作 TravelPlus 旧数据迁移源，保留但非本项目资产

## 本次改动清单

| 文件 | 动作 |
|---|---|
| `AGENTS.md` | L12 更新：Redis 描述改为主从+3哨兵集群（6380/6381 + 26379~81，密码 123456），并注明 6379 为其他项目 Redis、仅作迁移源 |
| `docs/travelplus/01-设计方案.md` | 标题后追加修正注记（正文未动） |
| `docs/travelplus/02-实施计划.md` | 标题后追加修正注记（正文未动） |
| `docs/travelplus/03-过程记录.md` | 标题后追加修正注记（正文未动） |
| `docs/travelplus/04-验证报告.md` | 标题后追加修正注记（正文未动） |
| `docs/travelplus/05-移动端前端设计与规划.md` | 标题后追加修正注记（正文未动） |
| `.omo/notepads/mobile-frontend/redis-cluster.md` | 文件末尾追加修正注记（append-only） |
| `.omo/notepads/mobile-frontend/redis-ownership-fix.md` | 本摘要（新建） |

## 未改动文件及原因

- `backend-contract.md`、`favorite-verify-notes.md`、`data-real-verify-notes.md`、`privacy-dm-verify-notes.md`：提及 6379 均为早期环境事实描述（"Redis(6379) Docker 均 Up"、"Redis：company-agent-redis:6379"），未明确声称"6379 是本项目 Redis"，按指示不逐个改，避免噪音。
- `git-history-notes.md`、`chat-verify-notes.md`、`order-timeout-init.md`：grep 检查未提及 6379，无需改动。
- 历史文档正文一律未篡改（仅追加注记）；未改代码/配置；未 git。