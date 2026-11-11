package com.travelplus.agent.core;

import com.travelplus.dto.Result;
import com.travelplus.entity.Attraction;
import com.travelplus.entity.TravelNote;
import com.travelplus.mapper.TravelNoteMapper;
import com.travelplus.service.IAttractionService;
import com.travelplus.service.ISearchService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 【Agent架构升级的实现】平台数据接地上下文构建器（从旧 AiChatController 平移独立成组件）。
 * <p>
 * 实现细节：
 * - 城市识别：最后一条提问包含 destinations 列表中的城市名即命中；未命中回退列表首位（销量最高城市）。
 * - 景点数据：复用 queryByDestination（attachListInfo 动态聚合口径），每条一行注入 system 提示词。
 * - 【游记RAG的实现】用城市 Top 景点名检索真实游记（ES 优先/LIKE 降级）→ 注入正文片段，
 *   最多 3 篇、每篇截 90 字；检索失败静默不影响主回答（RAG 是增强不是依赖）。
 * - 任何异常都返回空串走通用知识回答——接地是增强项，绝不能阻断对话主链路。
 *
 * @author TravelPlus
 */
@Slf4j
@Component
public class ContextBuilder {

    /** 注入 system 提示词的景点条数（单城销量 Top N） */
    private static final int CONTEXT_ATTRACTION_COUNT = 12;

    @Resource
    private IAttractionService attractionService;

    @Resource
    private ISearchService searchService;

    @Resource
    private TravelNoteMapper travelNoteMapper;

    /**
     * 按用户提问构建平台数据接地上下文（城市景点摘要 + 游记 RAG 片段）。
     *
     * @param question 用户最新提问（用于城市识别）
     * @return 注入 system 提示词的上下文文本；无可用数据时返回空串
     */
    public String buildCityContext(String question) {
        try {
            Result destResult = attractionService.queryDestinations();
            Object destData = destResult == null ? null : destResult.getData();
            if (!(destData instanceof List<?> destinations) || destinations.isEmpty()) {
                return "";
            }
            String city = String.valueOf(destinations.get(0));
            for (Object d : destinations) {
                String name = String.valueOf(d);
                if (!question.isEmpty() && question.contains(name)) {
                    city = name;
                    break;
                }
            }
            Result pageResult = attractionService.queryByDestination(city, 1, null, null);
            Object data = pageResult == null ? null : pageResult.getData();
            if (!(data instanceof List<?> list) || list.isEmpty()) {
                return "";
            }
            StringBuilder sb = new StringBuilder("平台景点数据（").append(city)
                    .append("，按人气排序，回答时优先引用）：\n");
            int count = 0;
            for (Object o : list) {
                if (!(o instanceof Attraction a) || count >= CONTEXT_ATTRACTION_COUNT) {
                    break;
                }
                double score = a.getAvgScore() != null && a.getAvgScore() > 0
                        ? a.getAvgScore() / 10
                        : (a.getScore() != null ? a.getScore() / 10.0 : 0);
                String price;
                if (a.getHasTicket() != null && a.getHasTicket() && a.getAvgPrice() != null && a.getAvgPrice() > 0) {
                    price = "¥" + (a.getAvgPrice() / 100) + "起";
                } else {
                    price = "免费";
                }
                sb.append("- ").append(a.getName() == null ? "未命名" : a.getName())
                        .append("｜评分").append(String.format("%.1f", score))
                        .append("｜").append(price)
                        .append("｜人气").append(a.getCheckinCount() != null ? a.getCheckinCount() : a.getSold())
                        .append('\n');
                count++;
            }
            appendNoteContext(list, sb);
            return sb.toString();
        } catch (Exception e) {
            // 接地失败不阻断回答：返回空上下文走通用知识
            log.warn("旅游AI助手景点上下文构建失败: {}", e.getMessage());
            return "";
        }
    }

    /** 游记 RAG：城市 Top 景点名检索真实游记 → 注入正文片段（最多 3 篇，失败静默） */
    private void appendNoteContext(List<?> list, StringBuilder sb) {
        try {
            StringBuilder noteSb = new StringBuilder();
            Set<Long> seen = new HashSet<>();
            int noteCount = 0;
            for (Object o : list) {
                if (!(o instanceof Attraction a) || a.getName() == null || noteCount >= 3) {
                    continue;
                }
                Result sr = searchService.search(a.getName(), 1);
                Object sData = sr.getData();
                if (!(sData instanceof Map<?, ?> sMap)) {
                    continue;
                }
                Object notesObj = sMap.get("notes");
                if (!(notesObj instanceof List<?> noteList)) {
                    continue;
                }
                for (Object n : noteList) {
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
                    snippet = snippet.substring(0, Math.min(snippet.length(), 90));
                    noteSb.append("《").append(noteTitle).append("》：").append(snippet).append("……\n");
                    noteCount++;
                    break;
                }
            }
            if (noteCount > 0) {
                sb.append("\n平台真实游记片段（用户亲测玩法，可参考其细节与口吻）：\n").append(noteSb);
            }
        } catch (Exception e) {
            log.warn("旅游AI助手游记 RAG 检索失败: {}", e.getMessage());
        }
    }
}
