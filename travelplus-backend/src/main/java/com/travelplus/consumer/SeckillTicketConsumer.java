package com.travelplus.consumer;

import cn.hutool.json.JSONUtil;
import com.travelplus.entity.TicketOrder;
import com.travelplus.service.ITicketOrderService;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 【kafka消息队列的实现】秒杀订单消息消费者
 * <p>
 * 链路说明：秒杀接口（TicketOrderServiceImpl.seckillTicket）Lua 预检通过后，
 * 向 topic=seckill-ticket-order 发送 JSON 字符串消息；本消费者监听该 topic，
 * 将 JSON 解析成 TicketOrder 后调用 createTicketOrder 落库并扣减库存，
 * 成功后才手动 ack 提交位移，失败则抛出异常交由 errorHandler 重试。
 */
@Slf4j
@Component
public class SeckillTicketConsumer {

    @Resource
    private ITicketOrderService ticketOrderService;

    /**
     * 【kafka消息队列的实现】消费秒杀订单消息
     * <p>
     * 实现细节：
     * - containerFactory 指定为 seckillTicketOrderKafkaListenerContainerFactory（见 KafkaConfig），
     *   该工厂配置了 AckMode.MANUAL_IMMEDIATE 与重试策略。
     * - 消息格式 = JSON 字符串：发送端 kafkaTemplate.send(topic, JSONUtil.toJsonStr(order))，
     *   故这里用 JSONUtil.toBean(String.valueOf(record.value()), TicketOrder.class) 反序列化。
     *   注意：不能强转 (TicketOrder) record.value()——value 是 String，强转必抛 ClassCastException；
     *   新建的 TicketOrder 只有 id/userId/ticketId 有值，其余字段为 null，JSON 中自动省略。
     * - 消费成功后调用 ack.acknowledge() 手动提交位移（与 MANUAL_IMMEDIATE 配套）；
     *   消费异常时不 ack、不吞异常，而是向上抛出，让 KafkaConfig.errorHandler()
     *   按 FixedBackOff(1000ms, 3次) 重试，重试耗尽后日志告警人工介入。
     */
    @KafkaListener(
            containerFactory = "seckillTicketOrderKafkaListenerContainerFactory",
            topics = "seckill-ticket-order"
    )
    public void processMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        try {
            // 获取消息内的数据：将 JSON 字符串解析为 TicketOrder 实体
            TicketOrder order = JSONUtil.toBean(String.valueOf(record.value()), TicketOrder.class);
            // 生成订单 扣减库存 等等操作（内部含 Redisson 锁 + stock>0 扣减 + save 落库）
            ticketOrderService.createTicketOrder(order);
            // 业务成功后提交确认ack（手动提交位移，落库成功才 ack）
            ack.acknowledge();
        } catch (Exception e) {
            log.error("消费异常: topic={}, offset={}, 原因={}", record.topic(), record.offset(), e.getMessage());
            // 不要 acknowledge(), 直接让异常向上抛出, 让 errorHandler 处理
            throw new RuntimeException("Deserialization failed", e);
        }
    }
}
