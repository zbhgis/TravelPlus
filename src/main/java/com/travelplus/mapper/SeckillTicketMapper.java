package com.travelplus.mapper;

import com.travelplus.entity.SeckillTicket;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 【秒杀门票数据访问的实现】秒杀门票表 tb_seckill_ticket（与门票一对一同主键）的 MyBatis-Plus Mapper，
 * 继承 BaseMapper 获得通用 CRUD 能力；库存初始装载与查询在秒杀链路 Service 层使用。
 * </p>
 *
 * @author TravelPlus
 * @since 2022-01-04
 */
public interface SeckillTicketMapper extends BaseMapper<SeckillTicket> {

}
