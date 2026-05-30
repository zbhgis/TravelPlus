package com.travelplus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 【秒杀门票实体的实现】秒杀门票表 tb_seckill_ticket，与门票 tb_ticket 一对一（主键即 ticket_id）。
 * 秒杀下单链路：Lua 脚本预检（存在性/未开始/已结束/一人一单/库存）→ 扣 Redis 库存 → 发 Kafka 消息，
 * 由 SeckillTicketConsumer 消费后经 createTicketOrder 落库扣减 DB 库存。
 * </p>
 *
 * @author TravelPlus
 * @since 2022-01-04
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_seckill_ticket")
public class SeckillTicket implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 关联的门票的id
     */
    @TableId(value = "ticket_id", type = IdType.INPUT)
    private Long ticketId;

    /**
     * 库存
     */
    private Integer stock;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 生效时间
     */
    private LocalDateTime beginTime;

    /**
     * 失效时间
     */
    private LocalDateTime endTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
