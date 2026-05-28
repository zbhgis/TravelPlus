package com.travelplus.controller;


import com.travelplus.dto.Result;
import com.travelplus.entity.AttractionType;
import com.travelplus.service.IAttractionTypeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author TravelPlus
 */
@RestController
@RequestMapping("/attraction-type")
public class AttractionTypeController {
    @Resource
    private IAttractionTypeService typeService;

    @GetMapping("list")
    public Result queryTypeList() {
        List<AttractionType> typeList = typeService
                .query().orderByAsc("sort").list();
        return Result.ok(typeList);
    }
}
