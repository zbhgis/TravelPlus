package com.travelplus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 【余额流水的实现】钱包余额流水表 tb_balance_record 实体，记录用户余额的每一次增减。
 * changeAmount 正=收入（充值/退款）、负=支出（消费）；type 取值：recharge=模拟充值 /
 * pay=订单消费 / refund=退款返还 / admin=运营调整；payMethod 为支付方式
 * （wechat=微信 / ali=支付宝 / balance=余额支付 / 空=其他）；description 为可读的变动描述。
 * 模拟充值时由 UserServiceImpl.recharge 写入（type='recharge'），秒杀券支付由
 * TicketOrderServiceImpl.payOrder 写入（type='pay'），钱包页余额明细直接分页读本表。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_balance_record")
public class BalanceRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户id
     */
    private Long userId;

    /**
     * 余额变动值（元），正=收入 负=支出
     */
    private BigDecimal changeAmount;

    /**
     * 变动类型：recharge=充值 / pay=消费 / refund=退款 / admin=调整
     */
    private String type;

    /**
     * 支付方式：wechat=微信 / ali=支付宝 / balance=余额支付 / 空=其他
     */
    private String payMethod;

    /**
     * 变动描述
     */
    private String description;

    /**
     * 变动时间
     */
    private LocalDateTime createTime;


}
