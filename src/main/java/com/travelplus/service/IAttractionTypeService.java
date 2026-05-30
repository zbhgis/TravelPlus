package com.travelplus.service;

import com.travelplus.entity.AttractionType;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 【景点类型服务的实现】景点类型服务接口：承载 tb_attraction_type 的 CRUD，供景点类型列表（/attraction-type/list）使用
 * <p>
 * 实现细节：继承 MyBatis-Plus IService，基础 CRUD 由框架提供；列表按 sort 排序查询见 Controller 使用方。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface IAttractionTypeService extends IService<AttractionType> {

}
