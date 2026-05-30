package com.travelplus.service;

import com.travelplus.entity.UserInfo;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 【用户信息服务的实现】用户详情信息服务接口：承载 tb_user_info（个性签名/城市/性别/生日/积分/隐私开关等）
 * <p>
 * 实现细节：继承 MyBatis-Plus IService，基础 CRUD 由框架提供；被用户主页（queryUserHome）、
 * 资料编辑（updateUserInfo）与关注列表隐私校验（FollowServiceImpl.queryFollowList）复用。
 *
 * @author TravelPlus
 * @since 2021-12-24
 */
public interface IUserInfoService extends IService<UserInfo> {

}
