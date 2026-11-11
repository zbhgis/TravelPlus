package com.travelplus.agent.support;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 【企业级客服Agent的实现】客服输入/输出护栏（Guardrails）。
 * <p>
 * 实现细节（对标企业 Agent 的 input/output guardrails 双层防护）：
 * - 输入层：控制字符清洗、长度截断、提示词注入检测、账号凭证套取检测；
 *   命中后不调用 LLM，直接返回安全话术（省 token、防越权、防社工）；
 * - 输出层：空回复兜底、系统提示词/内部工具名泄露检测、手机号脱敏；
 * - 所有规则均可解释、可审计（reason 写入日志与指标），不使用额外模型做审核，避免误杀与成本。
 *
 * @author TravelPlus
 */
@Slf4j
@Component
public class SupportGuardrails {

    /** C0 控制字符（保留 \t \n \r） */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0b\\x0c\\x0e-\\x1f\\x7f]");

    /** 提示词注入/越狱 */
    private static final Pattern PROMPT_INJECTION = Pattern.compile(
            "(忽略|无视|忘记|跳过).{0,8}(之前|以上|前面|所有|原有).{0,8}(指令|提示|规则|设定)"
                    + "|ignore\\s+(all\\s+)?(previous|above|prior)\\s+(instructions|prompts)"
                    + "|系统提示词|system\\s*prompt|开发者模式|developer\\s*mode"
                    + "|你现在是.{0,10}(不受限|无限制|新角色)|输出.{0,6}(你的|系统).{0,4}(提示|指令)",
            Pattern.CASE_INSENSITIVE);

    /** 套取密码/验证码/证件/银行卡 */
    private static final Pattern CREDENTIAL_PHISHING = Pattern.compile(
            "(把|发送|发我|给我|告诉我|提供|报一下).{0,10}(密码|验证码|身份证号?|银行卡号?|支付密码|短信码)"
                    + "|(密码|验证码|身份证号?|银行卡号?).{0,4}(是多少|发我|给我|报一下)",
            Pattern.CASE_INSENSITIVE);

    /** 系统提示词/内部工具名泄露 */
    private static final Pattern INTERNAL_LEAK = Pattern.compile(
            "系统提示词|system\\s*prompt|内部指令|内部工具|工具调用协议|get_my_orders|get_order_detail"
                    + "|get_my_account|get_seckill_deals|search_support_kb|request_human_handoff",
            Pattern.CASE_INSENSITIVE);

    /** 手机号（前后不能是数字，避免误伤 18 位订单号）；保留前 3 后 4 便于用户核对 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");

    private final SupportProperties props;

    public SupportGuardrails(SupportProperties props) {
        this.props = props;
    }

    /**
     * 输入清洗：去控制字符、trim、长度截断。
     *
     * @param raw 原始消息
     * @return 可进入后续流程的文本
     */
    public String clean(String raw) {
        String out = CONTROL_CHARS.matcher(raw == null ? "" : raw).replaceAll(" ").trim();
        int max = props.getMaxMessageLength();
        return out.length() > max ? out.substring(0, max) : out;
    }

    /**
     * 输入护栏：空消息 / 提示词注入 / 凭证套取。
     *
     * @param message 清洗后的消息
     * @return 判定结果；blocked=true 时 reply 为安全话术
     */
    public Decision checkInput(String message) {
        if (message == null || message.isBlank()) {
            return Decision.block("空消息", "请输入你想咨询的问题，我会尽力帮你解决～");
        }
        if (PROMPT_INJECTION.matcher(message).find()) {
            return Decision.block("提示词注入", "抱歉，我只能协助处理 TravelPlus 平台的使用问题。请告诉我你遇到的具体问题～");
        }
        if (CREDENTIAL_PHISHING.matcher(message).find()) {
            return Decision.block("凭证套取",
                    "为了保护账号安全，请不要向任何人提供密码、验证码、身份证或银行卡信息。"
                            + "平台客服不会索要这些信息。如果你遇到账号异常，可以回复「转人工」由人工客服核实。");
        }
        return Decision.pass(message);
    }

    /**
     * 输出护栏：空回复 / 内部信息泄露 / 手机号脱敏。
     *
     * @param reply 模型生成的回复
     * @return 判定结果；blocked=true 时使用安全兜底话术
     */
    public Decision checkOutput(String reply) {
        if (reply == null || reply.isBlank()) {
            return Decision.block("空回复", "抱歉，我暂时没有整理出合适的答复。你可以换个说法，或回复「转人工」由人工客服协助。");
        }
        if (INTERNAL_LEAK.matcher(reply).find()) {
            log.warn("【客服护栏】输出命中内部信息泄露规则，已拦截");
            return Decision.block("内部信息泄露",
                    "抱歉，这个信息我不能提供。请告诉我你遇到的具体问题，我继续帮你处理。");
        }
        return Decision.pass(PHONE.matcher(reply).replaceAll("$1****$2"));
    }

    /** 护栏判定：blocked=是否拦截；reply=最终可发送文本；reason=命中原因（审计） */
    public record Decision(boolean blocked, String reply, String reason) {

        public static Decision pass(String text) {
            return new Decision(false, text, null);
        }

        public static Decision block(String reason, String safeReply) {
            return new Decision(true, safeReply, reason);
        }
    }
}
