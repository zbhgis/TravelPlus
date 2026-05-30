
## 2026-08-12 createTicketOrder 分布式锁注释补全
- 文件: src/main/java/com/travelplus/service/impl/TicketOrderServiceImpl.java (createTicketOrder, L184-215)
- 改动: 仅方法内行注释增强，逻辑代码逐字未动; mvn compile EXIT=0
- 新增注释:
  - 锁创建: 【Redisson分布式锁的实现】key=lock:order:{userId} 按用户隔离; Kafka 异步多线程重复消费防护; SETNX+watchdog 自动续期(默认30s)防死锁
  - tryLock: 【锁获取的实现】非阻塞立即返回; 拿不到锁=该用户已有任务在执行, 直接拒绝避免无限阻塞
  - 失败分支: 【锁获取失败分支】return 后消费端正常 ack, 重复消息最终一致性靠 Redis seckill:order 一人一单
  - 扣库存: 【条件更新防超卖的实现】SET stock=stock-1 WHERE ticket_id=? AND stock>0 原子(MySQL行锁); Redis Lua 挡流量 + DB 条件更新保最终正确 双保险
  - 建单: 【建单的实现】扣减成功才 save, 同锁内串行, "扣了必有单、有单必扣了"
  - 释放锁: 【锁释放的实现】finally 保证异常也释放不死锁; Redisson 按线程/锁id校验不误释放
