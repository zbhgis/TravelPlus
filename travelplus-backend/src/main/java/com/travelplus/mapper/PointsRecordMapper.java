package com.travelplus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelplus.entity.PointsRecord;

/**
 * <p>
 * 【积分记录数据访问的实现】tb_points_record 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 积分流水写入（签到 +2 等）由 UserServiceImpl.sign 调用 insert，积分记录分页列表由
 * UserServiceImpl.queryPointsRecords 调用 selectPage。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface PointsRecordMapper extends BaseMapper<PointsRecord> {

}
