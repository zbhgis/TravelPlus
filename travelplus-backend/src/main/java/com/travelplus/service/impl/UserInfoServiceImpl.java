package com.travelplus.service.impl;

import com.travelplus.entity.UserInfo;
import com.travelplus.mapper.UserInfoMapper;
import com.travelplus.service.IUserInfoService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 【用户信息服务的实现】用户详情信息服务实现：承载 tb_user_info 的 CRUD（签名/城市/性别/生日/积分/隐私开关）
 * <p>
 * 实现细节：继承 MyBatis-Plus ServiceImpl，基础增删改查由框架提供；被用户主页（queryUserHome）、
 * 资料编辑（updateUserInfo）与关注列表隐私校验（FollowServiceImpl.queryFollowList）复用。
 *
 * @author TravelPlus
 * @since 2021-12-24
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
