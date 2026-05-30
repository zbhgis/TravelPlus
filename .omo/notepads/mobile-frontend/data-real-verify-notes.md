# 数据补真 / 足迹目的地 / 删结伴 / 会话删除 / 行程模板 —— 重启后端 + 真实验证报告

- 时间：2026-08-07
- 操作：重启后端（杀 8081 监听 java + mvn spring-boot:run 进程树 → start-backend.bat 重启 → 轮询根路径 200/401 确认存活）
- 结论：全部 5 项 ✅；后端保持运行；未做 git 操作；未改代码/前端

## ✅ 1. 游记点赞数据一致（DB liked == Redis ZCARD）
随机 5 篇游记（id 4/7/10/13/21），DB `tb_travel_note.liked` vs `redis-cli ZCARD note:liked:{id}` 全一致：

| id | title | DB liked | Redis ZCARD |
|----|-------|----------|-------------|
| 4 | 西湖断桥｜白堤漫步半日游… | 3 | 3 |
| 7 | 杭州龙井村｜九溪烟树茶园徒步… | 6 | 6 |
| 10 | 苏堤春晓｜六桥烟柳的春日漫游… | 8 | 8 |
| 13 | 满觉陇｜金秋桂花香满陇… | 5 | 5 |
| 21 | 北高峰日出｜凌晨四点上山… | 7 | 7 |

Redis 查询方式：本机无 redis-cli，用 `docker exec company-agent-redis redis-cli -h host.docker.internal -p 6379 ZCARD` 连宿主 6379。

## ✅ 2. checkins 足迹含 destination
- 登录用户1（13686869696，token T1）→ `GET /user/checkins/1?current=1` → data 6 条，**每条均含 destination**：鼓浪屿=厦门，龙井村/断桥/雷峰塔/灵隐寺/西湖风景名胜区=杭州。
- 用户1 打卡了鼓浪屿（厦门）+ 5 个杭州景点 → destination 去重 = {杭州, 厦门} = **2 ≥ 2** ✅
- 返回结构：{id, name, image, x, y, destination, checkinTime}，total=6。

## ✅ 3. 结伴同行已删除
- `GET /buddy/list`（带 T1 token）→ **HTTP 404** `{"status":404,"error":"Not Found","path":"/buddy/list"}`（接口已删；匿名请求 401 为拦截器先行，非接口存在）。
- `SHOW TABLES LIKE 'tb_travel_buddy'` → 空（表不存在）。

## ✅ 4. 私信会话删除 + 恢复演示
- 删除前：T1 `GET /chat/sessions` 含用户2（可可今天不吃肉，lastContent="你好，互关测试"）。
- T1 `DELETE /chat/session/2` → `{"success":true}`；再查 sessions → **仅剩用户 1010，用户2 会话消失**。
- 恢复演示：T1 `POST /chat/send` {toUserId:2, content:"你好"} → success data=12（互关 mutual=true，无限额）；再查 sessions → **用户2 会话已重建**（lastContent="你好"）。

## ✅ 5. 行程模板 + 模板一键创建 + 清理
- `GET /trip/templates`（匿名）→ success，**2 条**：
  - id=1 杭州经典三日游（destination=杭州，days 3 天：第1天-西湖[1,7,3]/第2天-灵隐[2,6,16]/第3天-龙井[13,9,20]）
  - id=2 厦门两日游（destination=厦门，days 2 天：第1天-鼓浪屿[21,26]/第2天-环岛路[23,22,25]）
  - 均含 title/destination/days 数组 ✅
- T1 `POST /trip` 用模板1 days 映射（attractionId=item、note=""、day.title 保留）→ success data=3；`GET /trip/3` 详情三级结构完整（3 days × 3 items，attractionName/Image JOIN 正常）。
- T1 `DELETE /trip/3` → success；`GET /trip/list` 只剩 id=2 原计划，测试数据已清理 ✅。

## 环境
- 后端：http://localhost:8081 存活（重启后轮询确认，根路径 401=需登录属预期，/trip/templates=200）
- DB：docker travelplus-mysql（3307）；Redis：宿主 6379；token 经 `authorization` 头 → Redis `login:token:{token}`
