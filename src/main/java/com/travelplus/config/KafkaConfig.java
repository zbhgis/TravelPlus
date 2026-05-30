package com.travelplus.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.net.SocketTimeoutException;
import java.util.HashMap;
import java.util.Map;

/**
 * 【kafka消息队列的实现】秒杀订单 Kafka 消费端配置类
 * <p>
 * 实现细节说明（为什么这样配）：
 * 1. 为秒杀订单消费链路（topic = seckill-ticket-order）提供独立的
 *    ConcurrentKafkaListenerContainerFactory，与 Spring Boot 自动配置的默认 factory 隔离，
 *    这样秒杀消息可单独定制 ack 模式、重试策略与反序列化器，不影响其他 topic 的消费。
 * 2. Ack 模式选择 MANUAL_IMMEDIATE（手动立即确认）：
 *    - 秒杀链路必须"先落库、后 ack"，即订单真正写入 tb_ticket_order 成功后，
 *      才调用 ack.acknowledge() 提交位移，避免"消息已消费但订单未落库"造成的订单丢失。
 *    - MANUAL_IMMEDIATE 下容器不会自动提交位移，只有代码显式 ack 才会提交，
 *      因此必须与 SeckillTicketConsumer 中的 ack.acknowledge() 配套使用。
 *    - 原注释代码写 AckMode.RECORD（每条记录处理后容器自动 ack），与手动 ack 冲突：
 *      RECORD 下容器会自动提交位移，手动 ack 失效，且处理失败时也无法靠重试兜底。
 * 3. 重试策略与 errorHandler：消费失败按 FixedBackOff(interval=1000ms, maxAttempts=3)
 *    最多重试 3 次、每次间隔 1000ms；所有重试耗尽后回调 log.error 输出告警日志，
 *    交由人工排查介入；可重试异常只认 SocketTimeoutException（网络抖动可重试），
 *    NullPointerException 视为不可重试（属于代码缺陷，重试无意义）。
 * 4. Key/Value 均使用 StringDeserializer：生产者端发的是 JSON 字符串
 *    （kafkaTemplate.send(topic, JSONUtil.toJsonStr(order))），不是对象字节序列化，
 *    因此这里仅把 Kafka 原始消息还原成 String，再由 SeckillTicketConsumer 用
 *    JSONUtil.toBean 解析为 TicketOrder，避免 LocalDateTime 等类型的序列化推断问题。
 */
@Slf4j
@Configuration
public class KafkaConfig {
    /**
     * 两次重试之间的等待时间（毫秒）。
     */
    @Value(value = "${kafka.backoff.interval:1000}")
    private Long interval;
    /**
     * 在放弃之前重新尝试操作的最大次数。
     */
    @Value(value = "${kafka.backoff.max_failure:3}")
    private Long maxAttempts;

    /**
     * 【kafka消息队列的实现】秒杀订单监听容器工厂
     * <p>
     * 实现细节：
     * - 绑定上面的 errorHandler() 与 consumerFactory()，@KafkaListener 通过
     *   containerFactory 属性指定本工厂（见 SeckillTicketConsumer）。
     * - AckMode 必须为 MANUAL_IMMEDIATE：秒杀消费逻辑里只有 createTicketOrder 落库成功后
     *   才手动 ack.acknowledge()，保证"落库成功才提交位移"，失败时不提交、交给 errorHandler 重试。
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> seckillTicketOrderKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        // 设置消费异常处理策略：重试3次(间隔1000ms)，耗尽后告警
        factory.setCommonErrorHandler(errorHandler());
        // Ack模式：MANUAL_IMMEDIATE，配合 SeckillTicketConsumer 手动 ack.acknowledge() 使用
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setConsumerFactory(consumerFactory());
        return factory;
    }

    /**
     * 【kafka消息队列的实现】消费异常处理策略
     * <p>
     * 实现细节：
     * - FixedBackOff(interval, maxAttempts)：消费失败后最多重试 maxAttempts=3 次，间隔 interval=1000ms。
     * - 重试耗尽后的回调：log.error 输出 topic/offset/异常原因 告警日志，提示人工排查介入。
     * - addRetryableExceptions(SocketTimeoutException)：网络类超时属于临时故障，可重试。
     * - addNotRetryableExceptions(NullPointerException)：空指针属于代码缺陷，重试无意义，直接走告警。
     */
    @Bean
    public DefaultErrorHandler errorHandler() {
        BackOff fixedBackOff = new FixedBackOff(interval, maxAttempts);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler((consumerRecord, exception) -> {
            // 当所有重试尝试都用尽时执行的逻辑
            // 告警，人工排查介入
            log.error("消费异常: topic={}, offset={}, 原因={}", consumerRecord.topic(), consumerRecord.offset(), exception.getMessage());
            // 模拟通讯软件通知或者电话通知告警

        }, fixedBackOff);
        // 我们可以指定哪些异常可重试，哪些不可重试
        errorHandler.addRetryableExceptions(SocketTimeoutException.class);
        errorHandler.addNotRetryableExceptions(NullPointerException.class);
        return errorHandler;
    }

    /**
     * 【kafka消息队列的实现】秒杀订单消费者工厂
     * <p>
     * 实现细节：
     * - bootstrap-servers = localhost:9092，group-id = your-group-id，与 application.yaml 的
     *   spring.kafka 配置保持一致（自建 factory 后以本配置为准）。
     * - KEY 与 VALUE 都使用 org.apache.kafka.common.serialization.StringDeserializer：
     *   发送端（TicketOrderServiceImpl.seckillTicket）发的是 JSON 字符串，故消费端只需还原成
     *   String，再由 SeckillTicketConsumer 用 JSONUtil.toBean 解析成 TicketOrder。
     *   注意：原注释代码误用了 Jackson 的 JsonDeserializer/StringDeserializer
     *   （com.fasterxml.jackson.databind.*），Jackson 类无法作为 Kafka 反序列化器使用，
     *   会导致启动失败，这里已修正为 Kafka 官方 StringDeserializer。
     */
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "your-group-id");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

}
