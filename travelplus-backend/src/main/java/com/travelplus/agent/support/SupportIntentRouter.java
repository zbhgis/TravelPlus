package com.travelplus.agent.support;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【企业级客服Agent的实现】客服意图路由器（Supervisor Routing 的轻量实现）。
 * <p>
 * 实现细节：
 * - 采用「确定性规则路由」而非额外一次 LLM 分类：客服场景意图词表稳定、规则可解释、
 *   零额外 token/延迟；模糊问题交回通用 Agent 处理（OTHER），不会误拦截；
 * - 优先级从高到低匹配，先命中先返回——「投诉退款」归投诉而不是订单；
 * - 输出置信度 = 命中关键词数加权（0.6~0.95），用于审计与后续低置信度转人工策略；
 * - 路由结果驱动「工具白名单 + 提示词策略 + 是否直接转人工」，是 Agent 治理的入口。
 *
 * @author TravelPlus
 */
@Component
public class SupportIntentRouter {

    /** 意图规则表（LinkedHashMap 保证优先级顺序） */
    private static final Map<SupportIntent, List<String>> RULES = new LinkedHashMap<>();

    static {
        RULES.put(SupportIntent.HUMAN, List.of(
                "转人工", "人工客服", "真人客服", "人工服务", "转接人工", "找客服", "客服在吗", "要人工"));
        RULES.put(SupportIntent.COMPLAINT, List.of(
                "投诉", "举报", "差评", "被骗", "欺诈", "盗号", "账号被盗", "封号", "赔偿",
                "起诉", "12315", "消协", "退款不到账", "一直不退", "不给退", "态度差", "我要维权"));
        RULES.put(SupportIntent.ORDER, List.of(
                "订单", "退款", "退票", "退单", "支付", "付款", "已付", "未付", "核销", "发票",
                "到账", "没到账", "扣款", "取消订单", "订单号", "买了", "购票", "门票", "下单", "支付失败"));
        RULES.put(SupportIntent.SECKILL, List.of(
                "秒杀", "抢票", "库存", "场次", "开抢", "限购", "抢不到", "还有票"));
        RULES.put(SupportIntent.ACCOUNT, List.of(
                "积分", "分数", "签到", "余额", "钱包", "秒杀券", "优惠券", "优惠卷", "券", "会员",
                "等级", "抵扣", "充值"));
        RULES.put(SupportIntent.FAQ, List.of(
                "登录", "登陆", "登不上", "打不开", "闪退", "收不到验证码", "注册", "密码", "验证码",
                "游记", "点赞", "评论", "关注", "粉丝", "私信", "隐私", "通知", "规则", "怎么", "如何",
                "在哪", "入口", "功能", "教程"));
    }

    /**
     * 路由一条用户消息。
     *
     * @param message 用户消息
     * @return 意图 + 置信度 + 命中信号
     */
    public Route route(String message) {
        return route(message, null);
    }

    /**
     * 【客服Agent完善】带上下文的路由：当前消息没有命中任何业务词（OTHER）时，
     * 继承上一轮意图，解决「第二笔呢」「那怎么办」这类多轮指代追问被当成闲聊的问题。
     *
     * @param message    当前消息
     * @param lastIntent 上一轮意图（可为 null）
     * @return 路由结果
     */
    public Route route(String message, SupportIntent lastIntent) {
        String text = message == null ? "" : message;
        for (Map.Entry<SupportIntent, List<String>> entry : RULES.entrySet()) {
            List<String> hits = new ArrayList<>();
            for (String keyword : entry.getValue()) {
                if (text.contains(keyword)) {
                    hits.add(keyword);
                }
            }
            if (!hits.isEmpty()) {
                double confidence = Math.min(0.95, 0.6 + hits.size() * 0.1);
                return new Route(entry.getKey(), confidence, List.copyOf(hits));
            }
        }
        /* 上下文继承：上一轮是明确的业务意图时，短追问沿用该意图 */
        if (lastIntent != null && lastIntent != SupportIntent.OTHER
                && lastIntent != SupportIntent.HUMAN && lastIntent != SupportIntent.COMPLAINT) {
            return new Route(lastIntent, 0.5, List.of("context:" + lastIntent.name()));
        }
        return new Route(SupportIntent.OTHER, 0.3, List.of());
    }

    /** 路由结果：intent=意图；confidence=置信度；signals=命中的关键词（审计用） */
    public record Route(SupportIntent intent, double confidence, List<String> signals) {
    }
}
