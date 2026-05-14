package com.travelplus.service;

import com.travelplus.dto.Result;
import com.travelplus.entity.TravelNote;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface ITravelNoteService extends IService<TravelNote> {

    Result queryHotTravelNote(Integer current);

    Result queryTravelNoteById(Long id);

    Result likeTravelNote(Long id);

    Result queryTravelNoteLikes(Long id);

    Result saveTravelNote(TravelNote note);

    Result queryTravelNoteOfFollow(Long max, Integer offset);

}
