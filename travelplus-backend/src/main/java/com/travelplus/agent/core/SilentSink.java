package com.travelplus.agent.core;

/**
 * 【客服Agent升级的实现】静默事件接收器：非流式调用（runToText）使用。
 * <p>
 * 实现细节：所有事件丢弃，编排层仍走同一条「记忆 → 提示词 → 工具循环 → 收敛」管线，
 * 最终答复由 {@link AgentOrchestrator.RunResult} 返回，避免为客服复制一套编排逻辑。
 *
 * @author TravelPlus
 */
public class SilentSink implements AgentSink {

    @Override
    public void meta(String runId, String sessionId) {
        // 非流式调用无事件通道
    }

    @Override
    public void delta(String text) {
        // 正文由 RunResult 返回，无需增量外发
    }

    @Override
    public void tool(String name, String label, String status, Long ms) {
        // 工具时间线仅供流式前端展示
    }

    @Override
    public void error(String code, String message) {
        // 错误由 RunResult 携带
    }

    @Override
    public void done() {
        // 非流式调用无连接需要关闭
    }
}
