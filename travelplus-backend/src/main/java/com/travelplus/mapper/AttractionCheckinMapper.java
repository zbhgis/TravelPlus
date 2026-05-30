package com.travelplus.mapper;

import com.travelplus.entity.AttractionCheckin;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 【打卡与足迹数据访问的实现】tb_attraction_checkin 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用
 * CRUD/分页能力；打卡幂等（先查是否已打卡）与足迹分页列表在 AttractionServiceImpl / UserServiceImpl
 * 基于条件构造器实现。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface AttractionCheckinMapper extends BaseMapper<AttractionCheckin> {

}
