package com.travelplus.mapper;

import com.travelplus.entity.UserInfo;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 【用户详情数据访问的实现】tb_user_info 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 用户主页详情（城市/介绍/粉丝/关注/生日/积分/隐私开关等）读写由 Service 层基于 userId 操作。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-24
 */
public interface UserInfoMapper extends BaseMapper<UserInfo> {

}
