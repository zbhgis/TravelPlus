package com.travelplus.mapper;

import com.travelplus.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * <p>
 * 【用户数据访问的实现】tb_user 表的 MyBatis-Plus Mapper，继承 BaseMapper 获得通用 CRUD 能力；
 * 登录/注册时按手机号查用户、保存用户等认证链路基于本 Mapper 完成。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface UserMapper extends BaseMapper<User> {

}
