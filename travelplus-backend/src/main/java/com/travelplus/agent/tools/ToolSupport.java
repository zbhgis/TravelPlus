package com.travelplus.agent.tools;

import com.travelplus.entity.Attraction;

import java.util.List;

/**
 * 【Agent架构升级的实现】工具实现层内部支撑：实体转换与展示字段格式化。
 * <p>
 * 实现细节：
 * - 泛型擦除防御：Service 返回的 List 经 JSON 反序列化后元素类型不定，castAttractions 安全过滤；
 * - 评分口径与平台一致：动态评分 avgScore 优先（已按 10 分制存库取 /10），回退原始 score /10；
 * - 票价口径：有票且 avgPrice>0 显示「¥{分/100}起」，否则「免费」。
 * 仅供 agent.tools 包内工具类复用（package 可见性不外溢）。
 *
 * @author TravelPlus
 */
final class ToolSupport {

    private ToolSupport() {
    }

    /** 列表元素安全转换为 Attraction */
    static List<Attraction> castAttractions(List<?> l) {
        List<Attraction> out = new java.util.ArrayList<>();
        if (l == null) {
            return out;
        }
        for (Object o : l) {
            if (o instanceof Attraction a) {
                out.add(a);
            }
        }
        return out;
    }

    /** 景点评分（10 分制一位小数口径） */
    static double score(Attraction a) {
        return a.getAvgScore() != null && a.getAvgScore() > 0
                ? a.getAvgScore() / 10
                : (a.getScore() != null ? a.getScore() / 10.0 : 0);
    }

    /** 景点票价展示文案 */
    static String price(Attraction a) {
        return (a.getHasTicket() != null && a.getHasTicket()
                && a.getAvgPrice() != null && a.getAvgPrice() > 0)
                ? "¥" + (a.getAvgPrice() / 100) + "起"
                : "免费";
    }
}
