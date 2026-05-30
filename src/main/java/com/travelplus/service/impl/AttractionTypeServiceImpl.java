package com.travelplus.service.impl;

import com.travelplus.entity.AttractionType;
import com.travelplus.mapper.AttractionTypeMapper;
import com.travelplus.service.IAttractionTypeService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 【景点类型服务的实现】景点类型服务实现：承载 tb_attraction_type 的 CRUD，供景点类型列表查询使用
 * <p>
 * 实现细节：继承 MyBatis-Plus ServiceImpl，基础增删改查由框架提供；无自定义业务方法。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Service
public class AttractionTypeServiceImpl extends ServiceImpl<AttractionTypeMapper, AttractionType> implements IAttractionTypeService {

}
