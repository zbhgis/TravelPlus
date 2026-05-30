package com.travelplus.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 【客服异步多线程的实现】客服 AI 调用专用异步线程池配置。
 * <p>
 * 背景与参数选择说明（为什么这样配）：
 * - 改造动机：SupportServiceImpl.chat 原同步调用 chatModel.chat()（等待 DeepSeek 网络响应
 *   1-3s），长时间占用 Tomcat 请求线程；改异步后，请求线程只做"校验 + 用户消息落库 + 提交任务"，
 *   毫秒级返回，AI 调用交给本线程池在后台执行，回复生成后异步落库 tb_chat_message，
 *   前端轮询 /chat/list?peerId=1021 获取 AI 回复。
 * - corePoolSize=2：AI 调用属 IO 密集型（等待网络响应期间 CPU 几乎空闲），2 个常驻线程已能覆盖
 *   日常并发；线程并非越多越好——过多线程带来内存占用与上下文切换开销，且 DeepSeek 有 QPS 上限，
 *   2-4 线程足够，避免占满 Tomcat 请求线程（默认 200，大量阻塞同样浪费）。
 * - maxPoolSize=4：核心线程忙且工作队列满时最多扩容到 4，应对短时并发峰值（多用户同时咨询）。
 * - queueCapacity=100：核心线程忙时任务先入队等待（不立即开新线程），队列满后再扩容到 max=4，
 *   平滑吸收瞬时突发，避免无界队列导致线程永不扩容、任务无限积压。
 * - 拒绝策略 CallerRunsPolicy：线程池满 + 队列满时，任务改由提交方（Tomcat 请求线程）直接执行，
 *   保证任何用户消息都不会被丢弃（AI 回复必然被处理、前端轮询必然收敛）；代价是极端情况下
 *   该请求线程阻塞一次，这是"保底不丢消息"的合理取舍。
 * - threadNamePrefix=travelplus-support-：线程命名规范，便于日志与 thread dump 快速定位客服线程。
 * - 生命周期：waitForTasksToCompleteOnShutdown=true，应用关闭时等待已提交任务执行完毕再销毁，
 *   避免"用户消息已落库、AI 回复落库中途被强制中断"造成回复丢失。
 * - 线程池隔离：本 Bean 命名为 supportTaskExecutor 明确归属客服链路，不与秒杀等其他异步场景混用，
 *   避免互相挤占线程。
 */
@Configuration
public class AsyncConfig {

    /**
     * 【客服异步多线程的实现】客服 AI 调用专用线程池 Bean。
     * 由 SupportServiceImpl 通过 @Qualifier("supportTaskExecutor") 注入，
     * 用于异步执行 generateAndPersistReply（DeepSeek 调用 + 回复落库）。
     *
     * @return 已按客服 IO 密集特性调优的 ThreadPoolTaskExecutor
     */
    @Bean("supportTaskExecutor")
    public ThreadPoolTaskExecutor supportTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // 核心线程数：2（AI 调用 IO 密集，2 个常驻线程覆盖日常并发，避免线程空转浪费）
        executor.setCorePoolSize(2);
        // 最大线程数：4（队列满后扩容，应对短时并发峰值）
        executor.setMaxPoolSize(4);
        // 工作队列容量：100（核心线程忙时任务先入队，平滑吸收突发，防止无界堆积）
        executor.setQueueCapacity(100);
        // 线程名前缀：travelplus-support-，便于日志与 thread dump 定位客服线程
        executor.setThreadNamePrefix("travelplus-support-");
        // 拒绝策略：CallerRunsPolicy（线程满+队列满时由提交线程执行，保证消息不丢）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        // 优雅关闭：容器关闭时等待已提交任务完成，避免 AI 回复落库被强制中断
        executor.setWaitForTasksToCompleteOnShutdown(true);
        return executor;
    }
}
