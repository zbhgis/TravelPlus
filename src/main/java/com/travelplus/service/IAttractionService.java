package com.travelplus.service;

import com.travelplus.dto.Result;
import com.travelplus.entity.Attraction;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface IAttractionService extends IService<Attraction> {

    Result queryById(Long id);

    Result update(Attraction attraction);

    Result queryAttractionByType(Integer typeId, Integer current, Double x, Double y);
}
