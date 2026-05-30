# TravelPlus 社交功能接口 — 实现摘要（orchestrator 统一测试用）

## 新增 4 个社交接口（mvn -q compile 通过，未重启后端）
全部接口契约以本文件为准，前端社交页按此对接。

## ① 用户主页聚合（【用户主页的实现】）
- `GET /user/home/{id}`（**公开**，MvcConfig exclude 已加 `/user/home/**`）
- data = `{user: UserDTO, info: UserInfo|null, fans, followee, likedCount, followed, noteCount}`
- 统计均为实时 COUNT/SUM（不读 UserInfo.fans/followee 冗余字段，保证与关注/游记列表一致）：
  - fans = COUNT(tb_follow.follow_user_id = id)
  - followee = COUNT(tb_follow.user_id = id)
  - likedCount = COALESCE(SUM(tb_travel_note.liked WHERE user_id = id), 0)
  - noteCount = COUNT(tb_travel_note.user_id = id)
- followed：UserHolder 判空，未登录=false；info 为 null 时前端判空
- 用户不存在 → fail"用户不存在"

## ② 关注/粉丝列表（【关注粉丝列表的实现】）
- `GET /follow/list/{id}?type=following|fans&current=页码`（**需登录**，/follow/** 拦截）
- type 必填，非法值 fail"type 参数必须为 following 或 fans"；current 默认 1，每页 10
- following：查 tb_follow.user_id=id 的 follow_user_id 集合；fans：查 tb_follow.follow_user_id=id 的 user_id 集合
- 分页：先对 tb_follow Page 分页（id 倒序），再 IN 查用户 + ORDER BY FIELD 保持顺序
- 每条 UserDTO 附 `isFollowed`（当前登录用户是否已关注，一次 Set 查询 O(1) 判断）
- data = List<UserDTO>，total = 关注关系总数

## ③ 本月签到日历（【签到功能的实现】）
- `GET /user/sign/list`（**需登录**）
- key 与 sign/signCount 完全一致：`sign:{userId}:yyyyMM`
- BITFIELD GET u{dayOfMonth} 0 一次取本月至今日所有位；位 i（0 起始）↔ 日期 i+1
- data = List<Integer> 已签到日期号数组（如 [1,5,8]），未签到/无记录返回 []

## ④ 删除游记（【游记删除的实现】）
- `DELETE /note/{id}`（**需登录**，/note/** 拦截）
- 作者校验：note.userId != UserHolder 当前用户 → fail"无权删除该游记"；重复删除走"游记不存在"（幂等）
- DB removeById 物理删除；Redis 清理：
  1. 删 `note:liked:{id}` 点赞集合（NOTE_LIKED_KEY）
  2. 从作者自身 Feed（FEED_KEY+作者id 的 ZSet）移除该 noteId（**简化**：粉丝 feed 的失效 noteId 拉取时查无即被过滤，遍历全粉丝清理复杂度=粉丝数，演示不做）

## 改动文件
- IUserService/UserServiceImpl/UserController：queryUserHome + signList
- IFollowService/FollowServiceImpl/FollowController：queryFollowList
- ITravelNoteService/TravelNoteServiceImpl/TravelNoteController：deleteNote
- UserDTO：新增 isFollowed 字段
- MvcConfig：exclude 追加 /user/home/**

## 鉴权边界（复用既有拦截器，未动）
- 公开：/user/home/{id}（新增放行）；其余 /user/** 需登录
- 需登录：/follow/list/{id}、/user/sign/list、DELETE /note/{id}

## 登录 NPE 修复（mvn -q compile 通过，未重启后端）
- 问题：`POST /user/login` 报 `Cannot invoke "Object.toString()" because "fieldValue" is null`
- 根因：上一任务给 UserDTO 新增的 `isFollowed`（Boolean 无默认值→null）被 `saveLoginToken()` 的
  `BeanUtil.beanToMap(setIgnoreNullValue(true) + setFieldValueEditor(v -> v.toString()))` 命中——
  hutool 的 fieldValueEditor 先于 ignoreNullValue 过滤被调用，null 字段直接 toString() 抛 NPE，
  登录/注册/验证码登录全链路受影响。
- 修复（两处，仅改 UserDTO.java 与 UserServiceImpl.java）：
  1. `UserDTO.isFollowed` 默认值 `= false`（避免 null 序列化与登录链路 NPE）
  2. `saveLoginToken()` 的 setFieldValueEditor 判空兜底：`fieldValue == null ? "" : fieldValue.toString()`，
     防御未来再新增可空字段

## 关注关系数据修复（mvn -q compile 通过，未重启后端）
- 问题：tb_follow 无唯一约束，历史测试+seed 产生重复行（如 (1,2)×2），主页粉丝数（COUNT 未去重）与关注/粉丝列表（IN 去重后）不一致
- 修复 A（DB，SQL 文件管道执行）：
  1. 去重：保留每组 (user_id, follow_user_id) 中 id 最小行，其余删除
  2. 唯一约束：`ALTER TABLE tb_follow ADD UNIQUE KEY uk_user_follow (user_id, follow_user_id);`
  3. 验证：`GROUP BY HAVING c>1` 无输出；当前 tb_follow 共 6 行：
     (1,1011)、(1,2)、(1,1010)、(1011,1)、(1012,1)、(1010,2)
- 修复 B（FollowServiceImpl.follow() 幂等，仅改此文件）：
  - 关注分支 save 前先 `query().eq(user_id).eq(follow_user_id).count()>0` 查重，已存在则跳过 insert（避免唯一约束冲突），
    并保证 Redis follows:{userId} 集合成员存在（SADD 幂等）
  - 取关分支 remove 逻辑不变（天然幂等）
  - 新增【关注功能的实现】注释：幂等原因（唯一约束 + 前端乐观更新重放）、Redis 集合与 DB 同步（followCommons 依赖交集）

## 连续签到算法修复（mvn -q compile 通过，未重启后端，仅改 UserServiceImpl.signCount()）
- 问题：signCount() 原逻辑从位图最低位（1号）开始数连续 1，语义错误——例：1、2号签、3~6号未签，返回 2，正确应为 0（今天6号未签）
- 修复：从今天（最高位 i=dayOfMonth-1）往前数连续签到，逐位 `(num & (1L << i)) != 0` 计数，遇到 0 停止；
  BITFIELD GET u{dayOfMonth} valueAt(0) 读取方式与 key（sign:{userId}:yyyyMM）均不变
- 方法注释更新为【签到功能的实现】：说明"从今天往前连续"语义、第 i 位↔日期 i+1 位映射

## 签到位序修正（mvn -q compile 通过，未重启后端，仅改 UserServiceImpl.signCount/signList）
- 位序事实（Redis 实测）：sign:1:202608 bit4=1(5号)、bit5=1(6号)；`GET u6 0`=3(0b000011)、`GET u7 0`=6(0b000110)
  → BITFIELD GET 把 offset0 作为结果 MSB，结果整数 LSB = Redis bit(dayOfMonth-1) = 今天；num 第 i 位（LSB 起）↔ 日期 dayOfMonth-i
- signCount()：**回滚上一次错误改法**，恢复原版 `while(true){ if((num&1)==0) break; count++; num>>>=1; }`
  （LSB=今天，右移即从今天往前数连续；当前 5、6号签到 → 连续 2 天）；注释更新说明位序
- signList()：解析改为 `if ((num & (1L << i)) != 0) signDays.add(dayOfMonth - i)`（i=0 是今天），
  收集为降序后 `signDays.sort(Integer::compareTo)` 升序返回（当前数据 → [5,6]）；注释更新说明位映射与排序原因
