# 移除「行程模板推荐」功能 — 摘要

日期：2026-08-11
范围：TravelPlus 后端 + 前端 + MySQL travelplus 库
状态：完成（编译 EXIT=0、后端接口验证、前端 Playwright 验证均通过）

## 改动清单

### 后端 `E:\AAAproject\travelplus`
- `src/main/java/com/travelplus/controller/TripController.java`
  - 删除 `import com.travelplus.service.ITripTemplateService`、字段 `tripTemplateService`
  - 删除 `GET /trip/templates` → `queryTripTemplates()` 方法及注释
  - 类 Javadoc 同步去掉「行程模板」描述
- 删除 4 个文件：`service/ITripTemplateService.java`、`service/impl/TripTemplateServiceImpl.java`、`mapper/TripTemplateMapper.java`、`entity/TripTemplate.java`
- `src/main/java/com/travelplus/config/MvcConfig.java`
  - 删除 LoginInterceptor 放行条目 `"/trip/templates"`（其余放行保留）
- `src/main/resources/db/travelplus.sql`
  - 删除 `tb_trip_template` 建表语句 + 2 条预置 INSERT（杭州经典三日游/厦门两日游）

### 前端 `E:\AAAproject\travelplus-frontend`
- `js/pages/travel.js`
  - 删除 `loadTripTemplates()` 函数（含一键复制逻辑）
  - 删除 `<div id="trip-templates-area"></div>` 与调用处
  - 文件头注释/核心链路注释去掉「行程模板推荐」
  - 清理因此变为未使用的 import：`post`（api.js）、`confirmDialog`（ui.js）
- `css/app.css`
  - 删除 `.trip-template-card:active`（L50 合并选择器）、`.trip-templates-list`/`.trip-template-card`/`.trip-template-body`/`.trip-template-title`/`.trip-template-dest`/`.trip-template-days`/`.trip-template-use-btn` 全部规则块

### 数据库
- docker MySQL（travelplus-mysql:3307 / travelplus 库）执行 `DROP TABLE IF EXISTS tb_trip_template`，SHOW TABLES 确认表已消失

## 验证结果
1. `mvn compile -q`（maven 3.9.11）→ EXIT=0
2. 后端 grep：`TripTemplate|tripTemplate|/trip/templates|行程模板` 在 `src/` 下 0 残留；前端 grep 同样 0 残留
3. 重启后端（start-backend.bat）后 `/attraction-type/list` → 200
4. `GET /trip/templates`：未登录 → 401（已不在放行列表）；带 token → 落到 `/{id}` 路由报 Long 转换失败（接口已删，不再匹配 `/templates`）；对照 `/trip/list` → 200（CRUD 保留）
5. Playwright（browser_run_code_unsafe，Network.setCacheDisabled）访问 `#/travel`：
   - `trip-templates-area` 不存在、`.trip-template-card` 数量 0、页面无「行程模板推荐」文本
   - 地图 `#travel-map`、附近 `#nearby-section`、概览 `.overview-card`×3、`#trip-plans-area`/「我的旅行计划」均在

## 注意事项
- 前端静态服务器无缓存头，浏览器可能磁盘缓存旧版 travel.js（Playwright 首次检测到残留 div 即此原因）；验证时需禁用缓存，真实用户硬刷新即可。
- 保留：`/trip` CRUD（tb_trip / tb_trip_day / tb_trip_day_item）、地图/附近景点/旅行概览/我的旅行计划。
