package com.travelplus.agent.support;

import org.springframework.stereotype.Component;

/**
 * 【企业级客服Agent的实现】按意图注入提示词策略（Prompt Policy）。
 * <p>
 * 实现细节：
 * - 基础提示词保持版本化、稳定；本类只追加「本轮意图」相关的动态约束，避免维护多份重复提示词；
 * - 约束核心：个人数据必须查工具、规则问题必须依据知识库、投诉/安全类必须转人工、禁止行程写操作；
 * - 意图与工具白名单由编排器同时下发，提示词与权限边界双保险。
 *
 * @author TravelPlus
 */
@Component
public class SupportPromptPolicy {

    /** 全局客服约束（每轮追加） */
    private static final String COMMON = """
            【本轮执行约束】
            - 你只能使用系统提供的工具，且本轮可用工具已按意图最小化；不要尝试调用工具清单外的能力。
            - 涉及订单、积分、余额、签到、秒杀库存等个人数据时，必须先调用工具核实；查不到就如实说明。
            - 回答平台规则时以注入的「平台知识库」为准，并在结尾注明来源标题。
            - 不承诺退款到账时间、赔偿、改价、解封；涉及投诉、账号安全、资金争议时调用 request_human_handoff。
            - 不要修改用户行程；用户需要规划或修改行程时，引导其使用「旅行」页或旅游助手。
            """;

    /**
     * 生成当前意图的附加指令。
     *
     * @param intent 路由意图
     * @return 追加到 system 的指令文本
     */
    public String instructions(SupportIntent intent) {
        return COMMON + switch (intent) {
            case ORDER -> """
                    【本轮意图：订单/支付】
                    先用 get_my_orders 或 get_order_detail 查真实订单，再回答状态、金额、核销与使用规则；
                    用户未提供订单号时先列订单让其确认；不要编造订单状态或退款进度。
                    """;
            case ACCOUNT -> """
                    【本轮意图：账户资产】
                    先用 get_my_account 查余额、积分、秒杀券与签到情况；解释积分抵扣规则时引用知识库；
                    不展示其他用户的数据，不猜测未查询到的资产。
                    """;
            case SECKILL -> """
                    【本轮意图：秒杀】
                    先用 get_seckill_deals 查当前进行中且有余量的场次；没有活动就明确告知；
                    解释限购、库存与异步落库规则时引用知识库，不承诺一定能抢到。
                    """;
            case FAQ -> """
                    【本轮意图：平台规则咨询】
                    优先依据注入的知识库分点回答；知识库没有覆盖的内容如实说明，并建议转人工或补充信息。
                    """;
            case OTHER -> """
                    【本轮意图：综合咨询】
                    先判断用户具体想解决什么问题；信息不足时用一句话澄清，再给出可执行的自助步骤。
                    """;
            default -> "";
        };
    }
}
