package com.travelplus.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.Result;
import com.travelplus.entity.TicketOrder;
import com.travelplus.mapper.TicketOrderMapper;
import com.travelplus.service.ISeckillTicketService;
import com.travelplus.service.ITicketOrderService;
import com.travelplus.utils.RedisIdWorker;
import com.travelplus.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.Collections;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Slf4j
@Service
public class TicketOrderServiceImpl extends ServiceImpl<TicketOrderMapper, TicketOrder> implements ITicketOrderService {

    @Resource
    private ISeckillTicketService seckillTicketService;
    @Resource
    private RedisIdWorker redisIdWorker;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result seckillTicket(Long ticketId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisIdWorker.nextId("order");
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                ticketId.toString(), userId.toString(), String.valueOf(orderId)
        );
        int r = result.intValue();
        // 2.判断结果
        if(result == 1){
            return Result.fail("库存不足");
        }
        if(result == 2){
            return Result.fail("重复下单");
        }
        // 有购买资格
        TicketOrder ticketOrder = new TicketOrder();
        ticketOrder.setId(orderId);    // 订单ID
        ticketOrder.setUserId(userId); // 用户ID
        ticketOrder.setTicketId(ticketId); // 门票ID

        // 3.指定Topic发送Kafka消息  【秒杀链路消息队列使用】-2
        kafkaTemplate.send("seckill-ticket-order", ticketOrder.toString());

        // 4.返回订单id
        return Result.ok(orderId);
    }

    public void createTicketOrder(TicketOrder ticketOrder) {
        Long userId = ticketOrder.getUserId();
        Long ticketId = ticketOrder.getTicketId();
        // 创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 判断
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }

        try {
            // 6.扣减库存
            boolean success = seckillTicketService.update()
                    .setSql("stock = stock - 1") // set stock = stock - 1
                    .eq("ticket_id", ticketId).gt("stock", 0) // where id = ? and stock > 0
                    .update();
            if (!success) {
                // 扣减失败
                log.error("库存不足！");
                return;
            }
            // 7.创建订单
            save(ticketOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }
}
