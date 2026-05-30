# 列表景点批量 attach（动态评分/打卡统计/门票信息）实现摘要
# mvn -q compile 通过（EXITCODE=0，仅 Maven 自身 sun.misc.Unsafe JDK24 告警）；未重启后端；未 git 操作；未改前端；未加新依赖

## 背景（bug）
- 详情 GET /attraction/{id} 已 attach avgScore/ratingCount/checkinCount/checkedIn/myRating；但列表接口
  （of/type、of/name、of/destination）未 attach → 主页卡片用原 score、详情用 avgScore → 评分不一致 bug

## ① 列表动态评分与打卡统计（【列表动态评分与打卡统计的实现】）
- AttractionServiceImpl 新增私有方法 `attachListInfo(List<Attraction> attractions)`：
  - 收集 id 列表 → 批量聚合查询（防 N+1）：
    - 评分：`SELECT attraction_id, AVG(score), COUNT(*) ... WHERE attraction_id IN (...) GROUP BY attraction_id`
      （ratingMapper.selectMaps，attraction_id -> {AVG, COUNT}）
    - 打卡：`SELECT attraction_id, COUNT(*) ... WHERE attraction_id IN (...) GROUP BY attraction_id`
      （checkinMapper.selectMaps，attraction_id -> checkinCount）
  - 逐个 set：avgScore（有评分=AVG×10 保留 1 位小数，与详情 attachRatingInfo 公式一致，**同源一致**；
    无评分不覆盖原 score——avgScore 保持原 score 值，前端有 avgScore 用之、无则回退原 score）；
    ratingCount（无评分为 0）；checkinCount（无打卡为 0，供"人来过"展示）
  - 列表统一调用点：queryAttractionByType（DB 分页分支 + GEO 分支）、queryByDestination、queryAttractionByName

## ② 门票信息（【门票信息的实现】）
- Attraction 实体新增 @TableField(exist=false) `hasTicket` / `hasSeckill`（Boolean）
- attachListInfo 同时批量判定：
  - hasTicket：`SELECT DISTINCT attraction_id FROM tb_ticket WHERE attraction_id IN (...)`（ticketMapper.selectObjs）
    ——有 tb_ticket=收费，无票景点 hasTicket=false（前端显示"免费"）
  - hasSeckill：`SELECT DISTINCT t.attraction_id FROM tb_seckill_ticket s JOIN tb_ticket t ON s.ticket_id=t.id
    WHERE t.attraction_id IN (...)`（SeckillTicketMapper 新增 @Select selectAttractionIdsWithSeckill，批量 join）
    ——有秒杀票=秒杀活动
- 种子事实：有票景点 1（普通+秒杀）、17（千岛湖秒杀）、18（乌镇秒杀）；其余 23 个免费

## ③ queryAttractionByName 迁入 service
- 原 AttractionController.queryAttractionByName 直查（query().like(...)）→ 迁为 service 方法
  `IAttractionService.queryAttractionByName(name, current)`（impl 同样 attachListInfo），
  Controller 委托 service；移除 Controller 不再使用的 Page/StrUtil/SystemConstants 导入

## 改动文件
- 修改（5）：entity/Attraction.java（hasTicket/hasSeckill）、mapper/SeckillTicketMapper.java（@Select join 方法）、
  service/IAttractionService.java（queryAttractionByName 声明）、service/impl/AttractionServiceImpl.java
  （attachListInfo + 三个列表接口调用 + queryAttractionByName impl + TicketMapper/SeckillTicketMapper 注入）、
  controller/AttractionController.java（of/name 委托 service + 去无用导入）

## 验证
- `mvn -q compile` → EXITCODE=0（仅 Maven 自身 JDK24 sun.misc.Unsafe 告警，与代码无关）
- 未重启后端（orchestrator 统一）；未 git 操作；未改前端；未加新依赖

## 前端对接契约
- 列表接口（of/type 两分支 / of/name / of/destination）data=List<Attraction> 新增：
  avgScore（有评分=均分×10 与详情一致，无评分=原 score）、ratingCount、checkinCount（"人来过"）、
  hasTicket（false=免费）、hasSeckill（true=秒杀入口）