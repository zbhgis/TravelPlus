# TTL 加随机值错峰过期（防缓存雪崩）

## 改动（2026-08-13）

### RedisConstants.java
- 新增 `CACHE_TTL_JITTER_SECONDS = 300L`：TTL 随机抖动范围（秒），基准 TTL 上浮 0~300 秒（5 分钟）。
  注释标注【缓存雪崩预防-错峰过期的实现】。

### CacheClient.java
- `set()` 重写：`finalSeconds = unit.toSeconds(time) + ThreadLocalRandom.current().nextLong(0, CACHE_TTL_JITTER_SECONDS)`，
  最终以 SECONDS 写入。import 新增 `java.util.concurrent.ThreadLocalRandom` 与静态 `CACHE_TTL_JITTER_SECONDS`。
- 方法 Javadoc 标注【缓存雪崩预防-错峰过期的实现】，说明：
  - 统一在 set()：所有走 set() 的缓存（queryWithPassThrough / queryWithMutex 写缓存）自动错峰，调用方无感；
  - 用 ThreadLocalRandom：高并发下避免 Math.random() 原子竞争；
  - 随机 0~300 秒（基准 30 分钟的 ~10-17%）：太小效果差、太大频繁回源；
  - 空值占位不受影响：CACHE_NULL_TTL 直调 stringRedisTemplate.set 不走本方法，保持固定 2 分钟；
  - 逻辑过期 setWithLogicalExpire 直调 stringRedisTemplate（不调 set()），无物理 TTL，不受影响（已确认无需改）。
- `setWithLogicalExpire` Javadoc 补注：直调 stringRedisTemplate 不走 set()，避免逻辑过期 key 被误加物理 TTL。

## 验证

- `mvn compile -q` EXIT=0。
- 后端重启后 `/attraction-type/list` HTTP 200。
- 访问 /attraction/1~8 后，Redis（redis-master:6380）实际 TTL 全部落在 1800~2100 秒区间且各不相同（写入时刻相近）：

| key | TTL(秒) |
|-----|--------|
| cache:attraction:1 | 1833 |
| cache:attraction:2 | 1997 |
| cache:attraction:3 | 1820 |
| cache:attraction:4 | 2095 |
| cache:attraction:5 | 1857 |
| cache:attraction:6 | 1821 |
| cache:attraction:7 | 1903 |
| cache:attraction:8 | 2054 |

- 空值占位不受影响：访问不存在 id=999999，布隆过滤拦截（未写缓存），`EXISTS cache:attraction:999999` = 0；
  空值占位路径本身仍直调 stringRedisTemplate 写固定 120 秒，未走 set()。