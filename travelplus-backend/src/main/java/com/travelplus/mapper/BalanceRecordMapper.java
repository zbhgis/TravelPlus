package com.travelplus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelplus.entity.BalanceRecord;

/**
 * <p>
 * 【余额流水的实现】tb_balance_record 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 余额流水写入（模拟充值 +100、订单支付 -应付等）由 UserServiceImpl.recharge /
 * TicketOrderServiceImpl.payOrder 调用 insert，余额流水分页列表由
 * UserServiceImpl.queryBalanceRecords 调用 selectPage。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface BalanceRecordMapper extends BaseMapper<BalanceRecord> {

}
