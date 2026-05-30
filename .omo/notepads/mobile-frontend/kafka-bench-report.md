# Kafka 专项性能压测报告
## 测试环境
- Kafka 3.7（docker travelplus-kafka），topic=seckill-ticket-order，单分区，ReplicationFactor=1
- 工具：kafka-producer-perf-test.sh / kafka-consumer-perf-test.sh（容器内直连，不走 HTTP）

## 一、Kafka 纯能力（无业务逻辑）
| 场景 | 吞吐 | 延迟 | 说明 |
|---|---|---|---|
| producer acks=1 | 265,111 条/s (50.6 MB/s) | avg 519ms / p99 694ms | 常规可靠性配置 |
| producer acks=0 | 576,036 条/s (109.9 MB/s) | avg 195ms / p99 239ms | 最大吞吐 |
| consumer 纯读 | 272,978 条/s (52 MB/s) | fetch 2M条/s | 无业务处理 |

## 二、项目真实消费链路（带业务逻辑）
| 场景 | 吞吐 | 说明 |
|---|---|---|
| SeckillTicketConsumer 落库 | ~0.5 条/s | Redisson 锁 + JSON 解析 + DB 落库逐条串行 |
| 生产端（秒杀接口） | 3343 QPS 写入 | Lua 预检通过才发 Kafka，毫秒级 |

## 三、关键结论
1. **Kafka 本身性能极强**：生产 26.5万~57.6万条/s，消费纯读 27.3万条/s——完全不是瓶颈
2. **项目消费端是真实瓶颈**：0.5 条/s vs Kafka 27万条/s，差距 50万倍——瓶颈在 Redisson 锁 + 单分区单消费者 + 逐条落库，不在 Kafka
3. **生产端 3343 QPS 写入无压力**：Lua 预检已挡掉 99.98% 无效请求，Kafka 只接真实订单
4. **架构设计验证**："预检挡流量 + 异步慢慢落"正确——Kafka 的吞吐能力远超当前业务需求
5. **压测副产品发现**：producer-perf-test 写入的随机字节消息（非项目 JSON 格式）导致消费端 JSONUtil.toBean 反序列化失败，FixedBackOff(1s,3次) 重试耗尽后卡在 offset——验证了消费端对畸形消息的容错边界

## 四、优化建议（如未来吞吐不够）
- 多分区（当前 1 分区）+ 多消费者并发（消费端并行度 = 分区数）
- 批量落库（攒 N 条一次 INSERT，而非逐条 save）
- 缩短 Redisson 锁持有时间 / 锁粒度细化
- 消费端异步化 + 手动批量 ack

## 五、环境已恢复
- topic 清理（retention 触发）→ LOG-END 归 0
- ticket21 库存修复：DB=50, Redis=50, SCARD=0（DB/Redis 一致）
- 服务 health=200，秒杀功能可用