# 旅行计划 CRUD 与地图数据接口 — 验证笔记

> 验证时间：2026-08-07（后端于 8081 端口重启后执行，用户1=13686869696/123456，用户2=13838411438/123456）
> 结论：全部通过 ✅，演示数据已保留（tripId=2 乌镇周末之旅）

## 1. 后端重启
- 杀掉旧进程（8081 监听 PID 30776 + maven spring-boot:run PID 45980）→ start-backend.bat 分离启动
- 轮询 /attraction-type/list 返回 200，就绪 ✅

## 2. 接口测试结果

### 地图数据 GET /map/points（匿名）
- ✅ success=true，返回 20 个景点，每条含 id/name/x/y/typeId/image/score
- ✅ 坐标 null 数=0，字段缺失数=0（x=经度、y=纬度，image=首图，score=×10 存储）

### 创建 POST /trip（T1，body 杭州三日游 2天3项）
- ✅ success=true，返回 tripId=1

### 列表 GET /trip/list?current=1（T1）
- ✅ total=1，记录含 title/destination/startDate=2026-09-01/endDate=2026-09-03/createTime

### 详情 GET /trip/1（T1）
- ✅ 三级结构正确：days 按 dayIndex 升序（day1 西湖经典 2 items / day2 灵隐禅意 1 item）
- ✅ items 按 sort 升序（0,1），每条含 attractionId/attractionName/attractionImage/note/sort

### 权限（T2 非创建者访问 T1 的计划）
- ✅ GET /trip/1 → success=false, errorMsg=无权查看该计划
- ✅ DELETE /trip/1 → success=false, errorMsg=无权删除该计划

### 参数校验（POST /trip，T1）
- ✅ 缺 title → fail 计划标题不能为空
- ✅ endDate(09-05) < startDate(09-01) → fail 结束日期不能早于开始日期
- ✅ days=31（>30）→ fail 行程天数需在1~30天之间
- ✅ items[].attractionId=99999 → fail 景点不存在

### 删除 DELETE /trip/1（T1）+ 级联
- ✅ success=true；再查列表 total=0
- ✅ 级联删除验证（MySQL）：tb_trip=0、tb_trip_day=0、tb_trip_day_item=0

### 演示数据（保留）
- ✅ POST /trip 创建「乌镇周末之旅」（tripId=2，2026-08-22~08-23，2天3项），列表可见
- ✅ DB 落库确认：tb_trip=1、tb_trip_day=2、tb_trip_day_item=3

## 3. 附注
- 请求头统一用 authorization（RefreshTokenInterceptor 读取），未登录访问 /trip/** 返回 401
- /map/points 在 MvcConfig exclude 放行列表，匿名可访问，无需 token
