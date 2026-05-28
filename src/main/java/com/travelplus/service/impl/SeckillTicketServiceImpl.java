package com.travelplus.service.impl;

import com.travelplus.entity.SeckillTicket;
import com.travelplus.mapper.SeckillTicketMapper;
import com.travelplus.service.ISeckillTicketService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 秒杀门票表，与门票是一对一关系 服务实现类
 * </p>
 *
 * @author TravelPlus
 * @since 2022-01-04
 */
@Service
public class SeckillTicketServiceImpl extends ServiceImpl<SeckillTicketMapper, SeckillTicket> implements ISeckillTicketService {

}
