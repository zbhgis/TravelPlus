package com.travelplus.agent.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 【Agent架构升级的实现】SSE 事件统一写出器（结构化协议 v2）。
 * <p>
 * 实现细节：
 * - 协议事件（data 均为 JSON）：
 *   {"meta":{"runId","sessionId"}}   首事件：运行 id + 服务端会话 id（前端存储实现会话续连）
 *   {"delta":"增量文本"}             正文增量（打字机渲染）
 *   {"tool":{"name","label","status","ms"}} 工具执行时间线事件（status=running/done/failed）
 *   {"error":{"code","message"}}     类型化错误（RATE_LIMITED/LLM_UNAVAILABLE/LLM_ERROR/INTERNAL_ERROR）
 *   {"done":true}                    正常收敛事件
 * - 全部发送 try/catch 静默：客户端断开/超时是常态而非异常，不向上传播；
 * - 相比旧协议（仅 delta/tool 两个字符串事件），结构化事件让前端能渲染工具时间线、
 *   按错误码差异化提示、并拿到 sessionId 实现跨设备会话续连。
 *
 * @author TravelPlus
 */
@Slf4j
public class SseSink implements AgentSink {

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;

    public SseSink(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    /** 首事件：告知运行 id 与服务端会话 id */
    public void meta(String runId, String sessionId) {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode meta = payload.putObject("meta");
        meta.put("runId", runId);
        meta.put("sessionId", sessionId);
        send(payload);
    }

    /** 正文增量 */
    public void delta(String text) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("delta", text);
        send(payload);
    }

    /**
     * 思考态事件：LLM 往返前推送（工具回喂后的再思考尤其需要）——
     * 否则工具步骤全部 ✓ 后正文空窗 2~5s，用户观感是"卡在检索"。前端展示"正在思考…"动画行，
     * 收到首个 delta / 下一个 tool 事件即移除。
     */
    public void thinking() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("thinking", true);
        send(payload);
    }

    /** 工具时间线事件：name=工具名 label=中文进度文案 status=running|done|failed ms=耗时 */
    public void tool(String name, String label, String status, Long ms) {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode tool = payload.putObject("tool");
        tool.put("name", name);
        tool.put("label", label);
        tool.put("status", status);
        if (ms != null) {
            tool.put("ms", ms);
        }
        send(payload);
    }

    /** 类型化错误事件 */
    public void error(String code, String message) {
        ObjectNode payload = objectMapper.createObjectNode();
        ObjectNode err = payload.putObject("error");
        err.put("code", code);
        err.put("message", message);
        send(payload);
    }

    /** 【企业级客服Agent的实现】正文替换事件：输出护栏修正后覆盖前端已渲染内容 */
    @Override
    public void replace(String text) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("replace", text == null ? "" : text);
        send(payload);
    }

    /**
     * 收敛事件（前端据此解锁输入框）——协议终止 + 流关闭的唯一出口。
     * 必须同时 emitter.complete()：否则连接挂到 SseEmitter 超时（120s）才断，
     * 前端 reader.read() 一直不返回，finally 不执行，streaming 锁死导致无法继续对话。
     */
    public void done() {
        done(null);
    }

    /** 【企业级客服Agent的实现】带落库消息 id 的收敛事件（客服流式回复反馈用） */
    @Override
    public void done(Long messageId) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("done", true);
        if (messageId != null) {
            payload.put("messageId", messageId);
        }
        send(payload);
        try {
            emitter.complete();
        } catch (Exception ignore) {
            // 重复 complete / 客户端已断开：忽略
        }
    }

    /** 静默发送（客户端断开/超时忽略） */
    private void send(ObjectNode payload) {
        try {
            emitter.send(SseEmitter.event().data(payload.toString(), MediaType.APPLICATION_JSON));
        } catch (Exception ignore) {
            // 客户端已断开/超时：忽略
        }
    }
}
