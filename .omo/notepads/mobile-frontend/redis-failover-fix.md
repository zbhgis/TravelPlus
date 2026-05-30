# Redis 哨兵集群 failover 加固修复摘要

日期：2026-08-12
范围：docker/redis-sentinel/ 哨兵配置 + 应用侧 Lettuce/Redisson 配置加固
目标：根治故障转移瞬间 Windows 宿主 JVM 因解析容器内网名 edis-master 反复失败、连接重建风暴耗尽资源导致进程被杀的问题。

## 崩溃根因复述

- 现象：failover 演练（docker stop 当时 master 6381）后，JVM 在 22:59:53 突然死亡，日志无 Shutdown/OOM，期间大量
  UnknownHostException: Failed to resolve 'redis-master' [A(1)] after 2 queries + DnsErrorCauseException: NXDOMAIN。
- 根因链：Docker Desktop WSL2 下 JVM 跑在 Windows 宿主，无法解析容器内网 DNS 名 edis-master。
  哨兵 failover 瞬间向 Redisson/Lettuce 通告新 master 时短暂出现容器名（而非宿主可达 172.30.144.1），
  客户端反复解析 + 疯狂重试连接重建（多次 24 连接初始化），最终宿主 JVM 资源耗尽被系统终止（无 Java 日志直接被杀）。

## 修复项

### A. 哨兵配置加固（docker/redis-sentinel/sentinel{1,2,3}.conf）
- 新增 sentinel resolve-hostnames no：哨兵不解析主/从节点主机名，始终按 IP 处理（默认即 no，显式声明防误开）。
- 新增 sentinel announce-hostnames no：只向客户端通告 IP，绝不通告主机名，从通告侧消灭 UnknownHostException 缝隙。
- 确认 sentinel monitor mymaster 172.30.144.1 6380 2 用宿主可达 IP（非容器名），保持不变。
- 说明：Redis 无 sentinel-announce-ip 指令，哨兵自身对外地址由已存在的 sentinel announce-ip 172.30.144.1 承担。

### B. 从节点配置复核
- redis-slave command 已有 --replica-announce-ip 172.30.144.1 --replica-announce-port 6381，
  演练前 CONFIG GET replica-announce-ip 验证生效（返回 172.30.144.1 / 6381）。无需改动。

### C. 应用侧加固
- pplication.yaml：lettuce 增加 shutdown-timeout: 200ms，failover 瞬间若应用恰逢关闭/重启可快速释放连接池，避免拖死 JVM。
- RedissonConfig.java：未加额外连接池/超时/重试参数（保持默认 connectTimeout 10s、retryAttempts 3、retryInterval 1500ms，
  对正常瞬时抖动已足够），以注释说明 failover 重试行为已被哨兵 IP 通告修复兜底。

## 两轮演练结果（核心验证）

| 轮次 | 停掉容器 | 新 master（get-master-addr-by-name） | 30s 持续监控 | javaAlive | 日志 UnknownHostException |
|------|----------|--------------------------------------|--------------|-----------|---------------------------|
| 1    | redis-master(6380) | 172.30.144.1:6381（redis-slave 提升） | 18/18 HTTP 200 | True | 0 匹配 |
| 2    | redis-slave(6381)  | 172.30.144.1:6380（redis-master 恢复主位）| 18/18 HTTP 200 | True | 0 匹配 |

- failover 切换后通告地址始终为纯 IP（172.30.144.1:<port>），**无任何容器名缝隙**。
- /attraction-type/list、/user/wallet(401=鉴权正常返回)、/user/me(401) 均正常响应。
- 演练后恢复拓扑：master=172.30.144.1:6380，redis-slave 自动降为从（role:slave），5 容器全部 healthy。

## 结论

failover 通告路径已加固：哨兵 monitor/announce 全程走宿主可达 IP + 显式关闭主机名解析与通告，
配合从节点 replica-announce-ip，故障转移全程不再出现容器内网名，JVM 不再因 DNS 解析风暴被拖死。两轮正反演练均未崩溃。
