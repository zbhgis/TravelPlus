# 旅行主题功能 — 实现摘要（mvn -q compile 通过，未重启后端，未 git 操作，未改前端）

## ① 旅行计划 CRUD（【旅行计划的实现】）
- 三表结构（DDL 见 travelplus.sql + 已同步到运行库 travelplus，幂等 CREATE TABLE IF NOT EXISTS）：
  - `tb_trip`（计划主表：user_id 创建者 / title / destination / start_date / end_date / create_time / update_time）
  - `tb_trip_day`（日程：trip_id → trip，day_index 1..N，uk_trip_day(trip_id,day_index) 唯一，title 当日主题默认 ''）
  - `tb_trip_day_item`（景点安排：day_id → trip_day，attraction_id → tb_attraction，note 默认 ''，sort，idx_day 索引）
- 接口（全部需登录，/trip/** 被 LoginInterceptor 拦截，未登录 401）：
  - `POST /trip` body {title,destination,startDate,endDate,days:[{title,items:[{attractionId,note}]}]} → data=计划id
  - `GET /trip/list?current=` → data=List<{id,title,destination,startDate,endDate,createTime}>，total=总数（按 createTime 倒序，每页 10）
  - `GET /trip/{id}` → 三级详情 {id,title,destination,startDate,endDate,createTime,days:[{id,dayIndex,title,items:[{id,attractionId,attractionName,attractionImage,note,sort}]}]}
  - `DELETE /trip/{id}` → 成功 ok
- 关键实现：
  - 归属校验：所有操作 UserHolder 取当前用户，trip.userId 不匹配 → fail"无权查看该计划"/"无权删除该计划"
  - create 校验：title/destination 非空、end>=start、天数 1~30、每项 attractionId 存在（AttractionMapper.selectById 判空，否则 fail"景点不存在"）
  - 落库顺序（@Transactional）：save(trip) 回填 id → 逐天 insert TripDay（dayIndex=列表序+1）→ 逐条 insert TripDayItem（sort=列表序，note/title 空串兜底）
  - 级联删除（@Transactional）：先按 day_id IN 删 items → 按 trip_id 删 days → removeById 删 trip
  - 详情景点 JOIN：item 实时查 tb_attraction 取 name + images 首图（firstImage 取逗号首段）；景点已删除 → attractionName="景点已下架"、attractionImage=null（保留条目）
  - 注入 TripDayMapper/TripDayItemMapper/AttractionMapper 直连 Mapper，避免 Service 层循环依赖（沿用 ChatServiceImpl 经验）
- 新增文件：entity/Trip、TripDay、TripDayItem；mapper/TripMapper、TripDayMapper、TripDayItemMapper；dto/TripCreateDTO（含嵌套 TripDayDTO/TripItemDTO）；service/ITripService + impl/TripServiceImpl；controller/TripController

## ② 地图数据接口（【地图数据的实现】）
- `GET /map/points`（**公开**，无需登录）：MvcConfig LoginInterceptor exclude 新增 `/map/points`
- 数据来源：直接复用现有 tb_attraction 的 x/y 坐标，**不新增表、不改景点表结构**
- 返回 data=List<{id,name,x,y,typeId,image,score}>：image=images 逗号分隔首图；score 为 ×10 存储原值（与 /attraction/** 契约一致，前端 /10 展示）
- null 过滤：接口层过滤 x/y 为 null 的记录（兜底，保证地图标记点坐标合法）
- **坐标补全检查**：运行库 + 种子脚本 20 个景点均已有杭州/周边真实坐标（含西湖核心区），无 NULL 坐标，**无需补全、无需 UPDATE**
- 新增文件：controller/MapController（注入 AttractionMapper）

## 改动文件汇总
- 新增（11）：entity/Trip.java、entity/TripDay.java、entity/TripDayItem.java、mapper/TripMapper.java、mapper/TripDayMapper.java、mapper/TripDayItemMapper.java、dto/TripCreateDTO.java、service/ITripService.java、service/impl/TripServiceImpl.java、controller/TripController.java、controller/MapController.java
- 修改（2）：config/MvcConfig.java（exclude 追加 /map/points）、resources/db/travelplus.sql（三表 DDL 段，空数据）

## 验证
- `mvn -q compile` → EXITCODE=0（仅 Maven 自身 sun.misc.Unsafe JDK24 告警，非代码问题）
- 运行库已建三表（docker exec SHOW TABLES 确认）；未重启后端（orchestrator 统一）；未 git 操作；未加新依赖；未改既有接口/前端

## 前端对接契约
- 地图页：GET /map/points（公开）→ [{id,name,x,y,typeId,image,score}]，x=经度 y=纬度，score ÷10 展示
- 旅行计划页：POST /trip（需登录）建计划；GET /trip/list?current= 列表；GET /trip/{id} 详情（days 按 dayIndex 升序、items 按 sort 升序）；DELETE /trip/{id}
- 失败文案：标题/目的地空、结束日期早于开始、天数不在 1~30、景点不存在、计划不存在、无权查看/删除该计划、请先登录(401)