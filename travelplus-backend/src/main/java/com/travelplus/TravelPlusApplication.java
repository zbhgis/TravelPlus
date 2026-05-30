package com.travelplus;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan("com.travelplus.mapper")
@EnableRetry
@EnableKafka // 【kafka消息队列的实现】开启 Kafka 监听支持，否则 @KafkaListener 注解不会生效（秒杀订单消费链路依赖）
// 【客服异步多线程的实现】开启 Spring 异步支持：配合 config/AsyncConfig 的 supportTaskExecutor
// 线程池使用——客服 AI 调用（chatModel.chat 等待 DeepSeek 响应 1-3s）改为后台异步执行，
// 请求线程立即返回"思考中"，AI 回复由线程池线程生成并落库，避免阻塞 Tomcat 请求线程。
@EnableAsync
// 【订单超时的实现】开启 Spring 定时任务支持：@Scheduled 才生效——OrderTimeoutTask 每 60 秒扫描
// 取消超时未支付（>15 分钟）的秒杀订单并回补库存（DB+Redis）+释放秒杀资格。
@EnableScheduling
@SpringBootApplication
public class TravelPlusApplication {

    public static void main(String[] args) {
        SpringApplication.run(TravelPlusApplication.class, args);
    }

}
