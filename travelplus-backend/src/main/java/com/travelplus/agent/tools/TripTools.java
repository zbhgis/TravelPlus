package com.travelplus.agent.tools;

import com.travelplus.agent.annotation.AgentTool;
import com.travelplus.agent.annotation.ToolParam;
import com.travelplus.agent.core.AgentToolProvider;
import com.travelplus.agent.core.ToolPermission;
import com.travelplus.dto.TripCreateDTO;
import com.travelplus.dto.Result;
import com.travelplus.entity.Attraction;
import com.travelplus.entity.TripDayItem;
import com.travelplus.mapper.TripDayItemMapper;
import com.travelplus.service.IAttractionService;
import com.travelplus.service.ITripService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【Agent架构升级的实现】行程类 Agent 工具集（查列表/查日程/加景点/优化顺序/建计划）。
 * <p>
 * 实现细节：
 * - 全部标注 USER_REQUIRED：涉及个人信息与写操作，未登录由 ToolRegistry 返回 needLogin
 *   结构化错误，模型转述引导登录；用户身份始终取 UserHolder（编排器已显式注入），
 *   归属校验由 ITripService 内部完成，工具层不信任任何模型给出的用户参数。
 * - 方法只写纯业务编排（从旧 AiChatController.executeTool 平移），schema/参数校验/
 *   异常兜底全部由 ToolRegistry 统一处理——工具零防御代码。
 * - optimize_day_order：贪心就近排序（Haversine 球面距离），apply=false 只出建议、
   true 写回 sort——「先预览后确认」是写类工具的人机协同底线。
 *
 * @author TravelPlus
 */
@Slf4j
@Component
public class TripTools implements AgentToolProvider {

    @Resource
    private ITripService tripService;

    @Resource
    private IAttractionService attractionService;

    @Resource
    private TripDayItemMapper tripDayItemMapper;

    /** 查当前用户全部旅行计划 */
    @AgentTool(name = "get_my_trips",
            description = "获取当前用户在 TravelPlus 的全部旅行计划列表（id/标题/目的地/日期）",
            permission = ToolPermission.USER_REQUIRED)
    public Object getMyTrips() {
        Result r = tripService.list(1);
        return r.getData();
    }

    /** 查某计划完整日程 */
    @AgentTool(name = "get_trip_detail",
            description = "获取某个旅行计划的完整日程（第几天/景点顺序/备注）",
            permission = ToolPermission.USER_REQUIRED)
    public Object getTripDetail(
            @ToolParam(value = "tripId", description = "计划id", required = true) long tripId) {
        Result r = tripService.detail(tripId);
        return r.getData();
    }

    /** 平台景点加入计划某天 */
    @AgentTool(name = "add_to_trip",
            description = "把一个平台景点加入用户的某个旅行计划的某一天（追加到当天末尾）",
            permission = ToolPermission.USER_REQUIRED)
    public Object addToTrip(
            @ToolParam(value = "tripId", description = "计划id", required = true) long tripId,
            @ToolParam(value = "dayIndex", description = "第几天，不传默认第1天；超出已有天数会顺延新建一天") int dayIndex,
            @ToolParam(value = "attractionId", description = "景点id（必须来自 search_attractions 的返回）", required = true) long attractionId,
            @ToolParam(value = "note", description = "安排备注，可选") String note) {
        Result r = tripService.addItem(tripId, dayIndex <= 0 ? 1 : dayIndex, attractionId,
                note == null ? "" : note);
        Map<String, Object> out = new LinkedHashMap<>();
        if (r.getSuccess() != null && r.getSuccess()) {
            out.put("ok", true);
            out.put("dayIndex", r.getData());
            out.put("message", "已加入第 " + r.getData() + " 天");
            return out;
        }
        out.put("ok", false);
        out.put("error", r.getErrorMsg() == null ? "加入失败" : r.getErrorMsg());
        return out;
    }

    /** 就近优化某天顺序（先建议后写入） */
    @AgentTool(name = "optimize_day_order",
            description = "优化某旅行计划某一天的景点游玩顺序：按景点地理位置做就近排序，返回优化前后总距离对比。"
                    + "先 apply=false 查看建议并转述给用户，用户明确同意后再 apply=true 写入",
            permission = ToolPermission.USER_REQUIRED)
    public Object optimizeDayOrder(
            @ToolParam(value = "tripId", description = "计划id", required = true) long tripId,
            @ToolParam(value = "dayIndex", description = "第几天，默认第1天") int dayIndex,
            @ToolParam(value = "apply", description = "false=只看建议（默认），true=把优化后的顺序写入行程") boolean apply) {
        Result d = tripService.detail(tripId);
        if (!(d.getData() instanceof Map<?, ?> tripMap)) {
            return Map.of("error", "计划不存在");
        }
        /* 定位目标天的条目列表 */
        List<Map<String, Object>> targetItems = null;
        if (tripMap.get("days") instanceof List<?> dayList) {
            for (Object dayObj : dayList) {
                if (dayObj instanceof Map<?, ?> dayMap
                        && dayMap.get("dayIndex") instanceof Number dn && dn.intValue() == dayIndex) {
                    if (dayMap.get("items") instanceof List<?> il) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> items = (List<Map<String, Object>>) il;
                        targetItems = items;
                    }
                    break;
                }
            }
        }
        if (targetItems == null) {
            return Map.of("error", "未找到该天日程");
        }
        /* 只处理有坐标的景点，贪心就近排序（起点固定第一个） */
        List<Map<String, Object>> geo = new ArrayList<>();
        for (Map<String, Object> it : targetItems) {
            Object x = it.get("attractionX"), y = it.get("attractionY");
            if (x instanceof Number nx && y instanceof Number ny) {
                it.put("_x", nx.doubleValue());
                it.put("_y", ny.doubleValue());
                geo.add(it);
            }
        }
        if (geo.size() < 2) {
            return Map.of("error", "该天有效坐标景点不足 2 个，无需优化");
        }
        double beforeKm = 0;
        for (int i = 0; i < geo.size() - 1; i++) {
            beforeKm += haversineKm(geo.get(i), geo.get(i + 1));
        }
        /* 贪心最近邻 */
        List<Map<String, Object>> ordered = new ArrayList<>();
        List<Boolean> used = new ArrayList<>(geo.size());
        for (int i = 0; i < geo.size(); i++) {
            used.add(false);
        }
        Map<String, Object> cur = geo.get(0);
        ordered.add(cur);
        used.set(0, true);
        double afterKm = 0;
        for (int step = 1; step < geo.size(); step++) {
            Map<String, Object> nearest = null;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < geo.size(); i++) {
                if (used.get(i)) {
                    continue;
                }
                double dist = haversineKm(cur, geo.get(i));
                if (dist < best) {
                    best = dist;
                    nearest = geo.get(i);
                }
            }
            afterKm += best;
            ordered.add(nearest);
            used.set(geo.indexOf(nearest), true);
            cur = nearest;
        }
        boolean changed = false;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i) != geo.get(i)) {
                changed = true;
                break;
            }
        }
        /* apply=true 才写库：写类动作必须有显式确认（人机协同） */
        if (apply) {
            if (!changed) {
                return Map.of("applied", true, "message", "当前顺序已是最优，无需调整");
            }
            for (int i = 0; i < ordered.size(); i++) {
                if (ordered.get(i).get("id") instanceof Number nid) {
                    TripDayItem it = tripDayItemMapper.selectById(nid.longValue());
                    if (it != null) {
                        it.setSort(i);
                        tripDayItemMapper.updateById(it);
                    }
                }
            }
        }
        List<String> orderNames = new ArrayList<>();
        for (Map<String, Object> it : ordered) {
            orderNames.add(String.valueOf(it.get("attractionName")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("apply", apply);
        out.put("changed", changed);
        out.put("beforeKm", Math.round(beforeKm * 10) / 10.0);
        out.put("afterKm", Math.round(afterKm * 10) / 10.0);
        out.put("suggestedOrder", orderNames);
        return out;
    }

    /** 对话行程一键落库为真实计划 */
    @AgentTool(name = "create_trip",
            description = "把对话中确定的行程一键创建为用户真实的旅行计划（会出现在用户行程列表）。"
                    + "days[].items[].name 必须使用平台景点数据或 search_attractions 返回的真实景点名；"
                    + "用户未给日期时省略 startDate 自动按一周后出发",
            permission = ToolPermission.USER_REQUIRED)
    public Object createTrip(
            @ToolParam(value = "destination", description = "目的地城市", required = true) String destination,
            @ToolParam(value = "days", description = "每日安排数组", required = true) com.fasterxml.jackson.databind.JsonNode days,
            @ToolParam(value = "title", description = "计划标题，可选，缺省自动生成") String title,
            @ToolParam(value = "startDate", description = "出发日期 yyyy-MM-dd，可选") String startDate) {
        if (destination == null || destination.isBlank() || !days.isArray() || days.isEmpty()) {
            return Map.of("error", "参数不完整：destination 与 days 必填");
        }
        TripCreateDTO dto = new TripCreateDTO();
        dto.setTitle(title == null || title.isBlank()
                ? destination.trim() + "·" + days.size() + "日游" : title.trim());
        dto.setDestination(destination.trim());
        LocalDate start = LocalDate.now().plusDays(7);
        if (startDate != null && !startDate.isBlank()) {
            start = LocalDate.parse(startDate);
        }
        dto.setStartDate(start);
        dto.setEndDate(start.plusDays(days.size() - 1L));
        /* 景点名 → 平台真实景点 id（不存在的景点报错回喂，模型可换名重试） */
        List<String> notFound = new ArrayList<>();
        List<TripCreateDTO.TripDayDTO> dayDTOs = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode dn : days) {
            TripCreateDTO.TripDayDTO dd = new TripCreateDTO.TripDayDTO();
            dd.setTitle(dn.path("title").asText(""));
            List<TripCreateDTO.TripItemDTO> items = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode in : dn.path("items")) {
                String name = in.path("name").asText("").trim();
                Long id = resolveAttractionId(name);
                if (id == null) {
                    notFound.add(name);
                    continue;
                }
                TripCreateDTO.TripItemDTO it = new TripCreateDTO.TripItemDTO();
                it.setAttractionId(id);
                it.setNote(in.path("note").asText(""));
                items.add(it);
            }
            dd.setItems(items);
            dayDTOs.add(dd);
        }
        if (!notFound.isEmpty()) {
            return Map.of("error", "以下景点未在平台收录，请用 search_attractions 确认真实景点名后再创建："
                    + String.join("、", notFound));
        }
        dto.setDays(dayDTOs);
        Result r = tripService.create(dto);
        Map<String, Object> out = new LinkedHashMap<>();
        if (r.getSuccess() != null && r.getSuccess()) {
            out.put("ok", true);
            out.put("tripId", r.getData());
            out.put("title", dto.getTitle());
            out.put("days", dayDTOs.size());
            return out;
        }
        out.put("ok", false);
        out.put("error", r.getErrorMsg() == null ? "创建失败" : r.getErrorMsg());
        return out;
    }

    /**
     * 【一键生成行程的实现】按名称解析景点 id：优先精确同名，其次 LIKE 检索第一条；找不到返回 null
     */
    private Long resolveAttractionId(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            Result r = attractionService.queryAttractionByName(name, 1);
            List<Attraction> l = ToolSupport.castAttractions(r.getData() instanceof List<?> x ? x : List.of());
            for (Attraction a : l) {
                if (name.equals(a.getName())) {
                    return a.getId();
                }
            }
            return l.isEmpty() ? null : l.get(0).getId();
        } catch (Exception e) {
            log.warn("Agent 景点名解析失败 name={} err={}", name, e.getMessage());
            return null;
        }
    }

    /** 【行程顺序优化的实现】Haversine 球面距离（km），坐标从条目的临时 _x/_y 读取 */
    private double haversineKm(Map<String, Object> a, Map<String, Object> b) {
        double lat1 = (Double) a.get("_y"), lon1 = (Double) a.get("_x");
        double lat2 = (Double) b.get("_y"), lon2 = (Double) b.get("_x");
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6371 * 2 * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h));
    }
}
