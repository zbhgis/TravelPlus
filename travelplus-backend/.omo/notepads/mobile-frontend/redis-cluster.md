# Redis 主从 + 3 哨兵 高可用集群（redis-cluster）

> 日期：2026-08-12　涉及：docker-compose 集群搭建 / 数据迁移 / Spring+Redisson 哨兵改造 / 全量回归 / 故障转移演练

## 一、集群拓扑与访问信息

| 组件 | 容器名 | 端口 | 说明 |
|---|---|---|---|
| Master | redis-master | **6380** | 原主节点；演练后降级为从，当前为从节点 |
| Slave | redis-slave | **6381** | 原从节点；故障转移演练后被提升，**当前稳定 Master** |
| Sentinel1/2/3 | redis-sentinel1/2/3 | **26379 / 26380 / 26381** | 监控 master=`mymaster`，quorum=2，5s 主观下线 |

- 统一密码：**123456**（`requirepass`/`masterauth`/`sentinel auth-pass`，与 MySQL/Kafka 一致）
- 哨兵本身无密码（`protected-mode no`，未配 requirepass）
- 启动/停止：`docker compose -f docker/redis-sentinel/docker-compose.yml up -d` / `down`
- 网络：自定义 bridge `172.40.0.0/16`（避开 Docker 默认池与 WSL2 的 172.30.0.0/16）
- 持久化：master/slave 各挂 named volume + `--appendonly yes`

### 关键设计（为什么能跑通）
- **地址可达性**：Docker Desktop(WSL2 后端) 下容器内网 IP(172.40.0.x) 对 Windows 宿主不可达，且无管理员权限改 hosts 文件。因此哨兵 monitor、节点 `replica-announce-ip`、哨兵 `announce-ip` 全部用 **172.30.144.1**（WSL2 vEthernet 网关 IP，宿主与容器双向可达 hairpin）。应用连哨兵(127.0.0.1:26379~81)拿到的 master 地址即为 172.30.144.1:6380/6381，可直接连接；故障转移后也无需改配置。
- **Lua/锁走主**：`seckill.lua` 与 Redisson RLock 天然走主节点；主从异步复制下绝不能让秒杀库存/锁读从（会读到旧数据导致超卖/锁失效）。

## 二、数据迁移（旧 6379 → 新主 6380）

- 方式：旧容器 `company-agent-redis`（无密码，**保留作备份，应用已不再连它**）内 `redis-cli --scan` 遍历 384 个 key，逐个 `MIGRATE 172.30.144.1 6380 <key> 0 5000 AUTH 123456 COPY`（COPY 保留源 key）
- **384 keys，0 失败，耗时 0.89s**
- 覆盖 key 类型：login:token:*（294 个）、sign:*（BitMap）、seckill:stock:*/seckill:order:*、browse:*、note:liked:*/attraction:liked:*（ZSet）、feed:*/follows:*、icr:order:*（RedisIdWorker 自增计数，必须迁移避免订单 ID 冲突）、login:code:*
- 迁移后验证：新主 DBSIZE==384、从节点同步后 DBSIZE==384、key 前缀抽查齐全

## 三、Spring 配置改造（application.yaml）

```yaml
spring.data.redis:
  password: 123456                 # master/slave 认证密码（哨兵无密码，故不配 sentinel.password，避免误发 AUTH）
  sentinel:
    master: mymaster
    nodes: 127.0.0.1:26379,127.0.0.1:26380,127.0.0.1:26381
  lettuce.pool: ...                # 保留原有连接池
```
- 依据 Spring Boot 3.5.9 `RedisConnectionConfiguration` 源码确认：`spring.data.redis.password` → 哨兵返回的 master/slave 连接密码；`spring.data.redis.sentinel.password` → 哨兵连接密码（分开，我们未配）。
- 主从切换后 Lettuce 自动重连新主。

## 四、Redisson 改造（RedissonConfig）

```java
config.useSentinelServers()
    .setMasterName("mymaster")
    .addSentinelAddress("redis://127.0.0.1:26379", "redis://127.0.0.1:26380", "redis://127.0.0.1:26381")
    .setPassword("123456");        // master/slave 密码；哨兵无密码故不 setSentinelPassword
```
- 修复哨兵发现：启动时 Redisson 会执行 `SENTINEL SENTINELS mymaster` 并按返回地址连其他哨兵。最初哨兵公布的是内网 IP(172.40.0.x)，宿主不可达导致启动失败；已给 3 个哨兵加 `sentinel announce-ip 172.30.144.1` + `announce-port`，问题解决（哨兵间通信经 hairpin 同样可用）。

## 五、回归结果（登录 13912345678/123456 → user 1010）

| 场景 | 结果 | 验证点 |
|---|---|---|
| 登录 | ✅ | 新 token 写入新主 6380，`login:token:*`=295 |
| 钱包 /user/wallet | ✅ | balance=470.50, points=160, couponCount=1 |
| 签到 /user/sign | ✅ | BitMap 读写正常，sign:1010:202608 天 11(迁移)+天 12(新写) |
| 景点详情(浏览写入) | ✅ | browse:1010 含迁移的 attraction:18 + 新增 attraction:29 |
| 浏览记录 /user/browse/list | ✅ | 正常返回 |
| 点赞 /note/like/4 | ✅ | note:liked:4 正常写入（ZSet） |
| 搜索 /search（ES） | ✅ | success=true，ES 链路无影响 |
| AI 客服 /support/chat | ✅ | 正常返回 |
| 秒杀 /ticket-order/seckill/10 | ✅ | Lua 走主：Redis 库存-1、seckill:order 记录、Kafka 异步落库成功（详见下节） |
| 秒杀 /ticket-order/seckill/21、22 | ✅ | 修 bug 后两次均 Redis==DB 一致（49/49、28/28），无重试无错误 |

### 秒杀链路发现并修复的问题（重要）
- **现象**：首次（JVM 启动后第一个 Redisson 锁操作）偶发 `unlock` 抛 `attempt to unlock lock, not locked by current thread`，异常掩盖建单结果 → Kafka FixedBackOff×3 重试 → 每次重试重复执行 `stock-1`+重复 INSERT，造成库存漂移（一次瞬态 = DB 库存多扣 3）。
- **根因**：哨兵模式下 JVM 启动后首次使用 Redisson 锁与哨兵拓扑初始化存在竞态，锁写入偶发未真正落在主节点。
- **修复**：`TicketOrderServiceImpl.createTicketOrder` 的 finally 中先 `isHeldByCurrentThread()` 再 `unlock()`（锁语义不变，只释放自己持有的锁；异常不再外抛 → 不触发 Kafka 重试 → 不再重复扣库存）。已验证：重启后首个消费消息（ticket 21）无错误、无重试、Redis==DB。
- **测试残留修复**：ticket 10 因重试级联 DB 库存 10→6，已校正回 9（与 Redis 一致）。

## 六、故障转移演练记录

1. `docker stop redis-master` → 哨兵 5s 判定主观下线、quorum=2 客观下线 → **自动 failover**
2. 15s 后 `sentinel master mymaster`：**ip=172.30.144.1, port=6381, flags=master**（从节点自动提升）
3. **应用无感知**：切换后 health 200、/user/wallet 正常、签到返回"今天已签到"、login token 在 6381 有效、浏览写入(attraction:24)落在 6381
4. 恢复 `docker start redis-master` → 15s 后：role=slave、`master_link_status=up`、全量同步完成；新主 connected_slaves=1（slave0=172.30.144.1:6380, offset 同步, lag=1）
5. 演练后终态：**redis-slave(6381) 为稳定 master**，redis-master(6380) 为已同步从节点；两节点 DBSIZE 一致（387）；5 容器全部 healthy
6. 应用在演练全程无需重启、无需改配置

## 七、注意事项 / 后续

- 旧容器 `company-agent-redis`（6379）保留作备份未删除；应用已不连它。
- 172.30.144.1 为本机 WSL2 网关 IP，换机器需替换 compose/sentinel conf 中的该地址（`ipconfig` 查 WSL 适配器 IPv4）。
- 演练后的秒杀测试订单：t10(625258525748101128)、t21(625259930202406922)、t22(625259376151625737) 均为 status=1 未支付，15 分钟后由 OrderTimeoutTask 自动取消并回补库存（Redis INCR + SREM，走新主）。
- 若要手动切回原主：`docker exec redis-sentinel1 redis-cli -p 26379 sentinel failover mymaster` 即可（当前无必要）。

---

> ⚠️ 修正注记（2026-08）：本文档中"Redis 6379 / company-agent-redis（复用）"的表述为早期阶段事实——**6379（company-agent-redis）实为其他项目的 Redis**，TravelPlus 仅曾以它为数据迁移源（384 keys 迁入集群，见上文第二节），迁移后应用配置已完全指向哨兵集群（127.0.0.1:26379~81），6379 容器仅保留作备份、不属于本项目。TravelPlus 现用 Redis 集群：redis-master 6380 / redis-slave 6381 + 哨兵 26379/26380/26381，统一密码 123456，见 docker/redis-sentinel/docker-compose.yml 与 AGENTS.md。
