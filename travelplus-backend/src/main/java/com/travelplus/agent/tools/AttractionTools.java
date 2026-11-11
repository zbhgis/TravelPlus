package com.travelplus.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelplus.agent.annotation.AgentTool;
import com.travelplus.agent.annotation.ToolParam;
import com.travelplus.agent.core.AgentToolProvider;
import com.travelplus.agent.core.ToolPermission;
import com.travelplus.dto.Result;
import com.travelplus.entity.Attraction;
import com.travelplus.entity.TravelNote;
import com.travelplus.mapper.TravelNoteMapper;
import com.travelplus.service.IAttractionService;
import com.travelplus.service.ISearchService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【Agent架构升级的实现】景点/内容类 Agent 工具集（搜景点 + 游记 RAG 检索工具化）。
 * <p>
 * 实现细节：
 * - 均为 PUBLIC 匿名可调（读类工具）：游客也能获得完整攻略体验；
 * - search_attractions：旧版平移——关键词/城市双入口，城市缺省回退平台销量最高城市；
 * - search_notes（新增）：把旧版「system 注入游记片段」的隐式 RAG 升级为显式检索工具——
 *   模型按需检索（用户问到玩法/体验/避坑时才调），上下文更准、token 更省，
 *   且检索过程对用户可见（工具时间线展示"正在检索平台游记…"）。
 *
 * @author TravelPlus
 */
@Slf4j
@Component
public class AttractionTools implements AgentToolProvider {

    /** 单次返回给模型的景点条数上限 */
    private static final int MAX_ATTRACTIONS = 10;

    /** 游记片段单篇截断长度 */
    private static final int NOTE_SNIPPET_LEN = 160;

    @Resource
    private IAttractionService attractionService;

    @Resource
    private ISearchService searchService;

    @Resource
    private TravelNoteMapper travelNoteMapper;

    /** 按城市或关键词搜索平台景点 */
    @AgentTool(name = "search_attractions",
            description = "按城市或关键词搜索 TravelPlus 平台收录的景点（返回 id/名称/评分/票价），"
                    + "要把景点加入行程或创建计划时必须先用它拿到 attractionId",
            permission = ToolPermission.PUBLIC)
    public Object searchAttractions(
            @ToolParam(value = "city", description = "城市名，如 杭州；不传默认平台销量最高城市") String city,
            @ToolParam(value = "keyword", description = "名称关键词，可选") String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        String ct = city == null ? "" : city.trim();
        List<Attraction> list;
        if (!kw.isEmpty()) {
            Result r = attractionService.queryAttractionByName(kw, 1);
            list = ToolSupport.castAttractions(r.getData() instanceof List<?> l ? l : List.of());
        } else {
            if (ct.isEmpty()) {
                Result d = attractionService.queryDestinations();
                if (d.getData() instanceof List<?> ds && !ds.isEmpty()) {
                    ct = String.valueOf(ds.get(0));
                }
            }
            Result r = attractionService.queryByDestination(ct, 1, null, null);
            list = ToolSupport.castAttractions(r.getData() instanceof List<?> l ? l : List.of());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Attraction a : list) {
            if (out.size() >= MAX_ATTRACTIONS) {
                break;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", a.getId());
            item.put("name", a.getName());
            item.put("score", Math.round(ToolSupport.score(a) * 10) / 10.0);
            item.put("price", ToolSupport.price(a));
            out.add(item);
        }
        return out;
    }

    /** 游记 RAG 显式检索工具 */
    @AgentTool(name = "search_notes",
            description = "按关键词检索平台真实用户游记，返回标题与正文片段（最多 3 篇），"
                    + "适合回答玩法/体验/避坑/美食等需要真实亲测细节的问题",
            permission = ToolPermission.PUBLIC)
    public Object searchNotes(
            @ToolParam(value = "keyword", description = "检索关键词，如 西湖", required = true) String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        if (kw.isEmpty()) {
            return Map.of("error", "keyword 不能为空");
        }
        Result sr = searchService.search(kw, 1);
        if (!(sr.getData() instanceof Map<?, ?> sMap) || !(sMap.get("notes") instanceof List<?> noteList)) {
            return emptyResult();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Object n : noteList) {
            if (out.size() >= 3) {
                break;
            }
            Long noteId = null;
            String noteTitle = "";
            if (n instanceof Map<?, ?> nm) {
                if (nm.get("id") instanceof Number num) {
                    noteId = num.longValue();
                }
                noteTitle = String.valueOf(nm.get("title"));
            }
            if (noteId == null || seen.contains(noteId)) {
                continue;
            }
            seen.add(noteId);
            TravelNote tn = travelNoteMapper.selectById(noteId);
            if (tn == null || tn.getContent() == null) {
                continue;
            }
            String snippet = tn.getContent().replaceAll("\\s+", " ");
            snippet = snippet.substring(0, Math.min(snippet.length(), NOTE_SNIPPET_LEN));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", noteTitle);
            item.put("snippet", snippet);
            out.add(item);
        }
        return out.isEmpty() ? emptyResult() : out;
    }

    /**
     * 空结果语义化：裸 [] 会让模型以为检索出错而换词反复重试（每次重试=一轮 2~5s 的 LLM 往返，
     * 用户观感是"一直在检索"）。明确告知"没检索到+该怎么做"，模型通常直接转向通用知识回答。
     */
    private Object emptyResult() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("results", List.of());
        out.put("hint", "未检索到相关游记（游记标题不含该关键词）。不要再换词重试，直接基于通用旅行知识回答即可");
        return out;
    }
}
