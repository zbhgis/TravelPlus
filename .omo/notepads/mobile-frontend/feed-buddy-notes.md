# Feed 修复 + 结伴同行 — 后端实现笔记

> 位置：`E:\AAAproject\travelplus`（Spring Boot 3.5.9 / MyBatis-Plus / Redis / MySQL 8）
> 日期：2026-08-07 ｜ 状态：已实现，`mvn -q compile` 通过，种子已同步运行库

## 一、关注 Feed 修复【关注Feed同步的实现】

### 根因
Feed 推送只在**发布游记那一刻**执行：`TravelNoteServiceImpl.saveTravelNote` 保存成功后，
把 noteId 以 `score=System.currentTimeMillis()` ZADD 进**当时**所有粉丝的收件箱
`feed:{粉丝id}`。若用户在被关注者发布**之后**才关注，关注前的历史游记永远不会被推入
自己的 Feed → 关注后"关注频道"看不到 TA 的历史帖子。

### 修复（FollowServiceImpl.follow 关注分支）
- 在 `isFollow=true` 分支，DB 落库 + Redis `follows:` SADD 成功之后、发通知之前，
  调用新增的 `backfillFollowFeed(followUserId, userId)`。
- 实现：`TravelNoteMapper` 查询被关注者全部游记，**只 select id, create_time 两列**
  （`SELECT id, create_time FROM tb_travel_note WHERE user_id = ?`）；
  逐个 `ZADD feed:{followerId}`，member=noteId，**score=createTime 的 epoch 毫秒**。
- 为什么 score 用 createTime 而非关注时刻：保证历史帖按"真实发布时间"落入时间轴
  （旧帖在后、新帖在前），与 `queryTravelNoteOfFollow` 的 ZREVRANGEBYSCORE 时间倒序
  滚动语义一致；用关注时刻会错误地把历史帖顶到最前，排序失真。
- 幂等：ZADD 对已存在 member 只更新 score 不产生重复成员，重复关注/重复请求天然安全。
- 时间换算：`createTime.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()`
  —— 数据源 URL 为 `serverTimezone=UTC`，驱动读出的 LocalDateTime 即 UTC 墙钟；
  与 `RedisIdWorker` 的 ZoneOffset.UTC 约定一致。
- 失败兜底：整体 try-catch，失败仅 `log.warn`，**不影响关注主流程结果**。
- 注：`saveTravelNote` 发布推送逻辑未改动（按要求保留）。

### 涉及文件
- `src/main/java/com/travelplus/service/impl/FollowServiceImpl.java`
  （新增 `TravelNoteMapper` 注入 + `backfillFollowFeed` + follow 分支调用 + 详细注释）

## 二、结伴同行【结伴同行的实现】

### 表结构（已同步运行库）
```sql
CREATE TABLE IF NOT EXISTS tb_travel_buddy (
  id BIGINT UNSIGNED PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT UNSIGNED NOT NULL COMMENT '发布者',
  destination VARCHAR(50) NOT NULL COMMENT '目的地',
  travel_date DATE NOT NULL COMMENT '出行日期',
  note VARCHAR(200) NOT NULL DEFAULT '' COMMENT '结伴说明',
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```
已通过 docker 管道（`docker exec -i travelplus-mysql mysql ... < temp.sql`，UTF-8 无 BOM）
应用到运行库，并写入 2 条种子（id=1 用户1010 厦门 2026-09-15；id=2 用户1012 杭州 2026-09-20）。

### API 契约（@RequestMapping("/buddy")，全部需登录，LoginInterceptor 已天然拦截）
| 方法 | 路径 | 请求 | 响应 data |
|---|---|---|---|
| POST | `/buddy` | body `{destination, travelDate, note}` | 新需求 id |
| GET | `/buddy/list?current=` | 分页，current 默认 1 | `List<Map>` + total |
| DELETE | `/buddy/{id}` | 路径 id | ok |

列表项结构：
`{id, destination, travelDate, note, createTime, user:{id, nickName, icon}}`

### 实现细节
- **发布** `BuddyServiceImpl.createBuddy`：校验顺序 登录态 → destination 非空 →
  travelDate 非空 → `travelDate.isBefore(LocalDate.now())` 过去日期 fail（当天/未来允许）→
  userId 服务端从 UserHolder 填充 → note 空串兜底 → 插入返回 id。
- **列表** `listBuddy`：`orderByDesc("create_time")` 分页（每页 MAX_PAGE_SIZE=10）；
  收集本页发布者 id 去重，**一次 `userService.listByIds` 批量查用户**（toMap 映射，防 N+1）；
  发布者已注销时 nickName/icon 空串兜底。
- **删除** `deleteBuddy`：不存在 fail"结伴信息不存在"（重复删幂等）；`userId != 当前用户`
  fail"无权删除"；通过则物理删除。
- 新增文件：
  - `entity/TravelBuddy.java`（@TableName("tb_travel_buddy")）
  - `mapper/TravelBuddyMapper.java`
  - `service/IBuddyService.java`
  - `service/impl/BuddyServiceImpl.java`
  - `controller/BuddyController.java`

### 种子同步
- `src/main/resources/db/travelplus.sql` 末尾新增 `tb_travel_buddy` 建表 + 2 条 INSERT（含注释）。
- 运行库已执行（临时脚本 `C:\Users\lenovo2026\AppData\Local\Temp\opencode\travel_buddy_seed.sql`，
  执行后保留供查证）。

## 三、验证
- `mvn -q compile` 通过（仅 Maven 运行时 sun.misc.Unsafe 弃用警告，与本代码无关）。
- 新类已生成：BuddyController/BuddyServiceImpl/FollowServiceImpl .class 均在 target/classes。
- 未重启后端（后端已在跑）；未做任何 git 操作；未改前端。
