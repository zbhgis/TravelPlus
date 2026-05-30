package com.travelplus;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.retry.annotation.EnableRetry;

@MapperScan("com.travelplus.mapper")
@EnableRetry
@EnableKafka // 【kafka消息队列的实现】开启 Kafka 监听支持，否则 @KafkaListener 注解不会生效（秒杀订单消费链路依赖）
@SpringBootApplication
public class TravelPlusApplication {

    public static void main(String[] args) {
        SpringApplication.run(TravelPlusApplication.class, args);
    }

}
