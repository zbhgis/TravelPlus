package com.travelplus.agent.support;

/**
 * 【企业级客服Agent的实现】客服意图分类。
 * <p>
 * 路由优先级：HUMAN &gt; COMPLAINT &gt; ORDER &gt; SECKILL &gt; ACCOUNT &gt; FAQ &gt; OTHER。
 * 意图决定：是否直接转人工、提示词策略、可用工具白名单（最小权限原则）。
 *
 * @author TravelPlus
 */
public enum SupportIntent {

    /** 明确要求人工客服 */
    HUMAN("人工"),

    /** 投诉/举报/账号安全/资金争议：直接进入人工工单 */
    COMPLAINT("投诉"),

    /** 订单、支付、退款、核销、发票 */
    ORDER("订单"),

    /** 秒杀活动、库存、场次、限购 */
    SECKILL("秒杀"),

    /** 积分、签到、余额、钱包、优惠券 */
    ACCOUNT("账户资产"),

    /** 平台规则与功能咨询（登录/游记/隐私等） */
    FAQ("规则咨询"),

    /** 其他/闲聊 */
    OTHER("其他");

    private final String label;

    SupportIntent(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
