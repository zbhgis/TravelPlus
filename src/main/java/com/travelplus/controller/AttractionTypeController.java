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
 * 【景点类型模块接口的实现】景点类型查询入口
 * <p>
 * 实现细节：
 * - 路由域：@RequestMapping("/attraction-type")，挂 /attraction-type/** 前缀。
 * - 匿名可访问：/attraction-type/** 在 MvcConfig 登录拦截器 exclude 列表放行，无需 token。
 * - 全量列表查询（不做分页）：类型数据量小且相对稳定，一次拉全量供前端做分类 Tab/筛选。
 *
 * @author TravelPlus
 */
@RestController
@RequestMapping("/attraction-type")
public class AttractionTypeController {
    @Resource
    private IAttractionTypeService typeService;

    /**
     * 【景点类型列表的实现】查询全部景点类型（按 sort 升序）
     * <p>
     * 实现细节：
     * - 直接 typeService.query().orderByAsc("sort").list() 全量查询并返回，
     *   data=List<AttractionType>，字段 {id, name, icon, sort}。
     * - 排序依据 sort 字段升序：前端"推荐/热门"等分类 Tab 的顺序由运营在数据中维护，
     *   新类型只要 sort 更大即排到后面，无需改前端代码。
     * @return data=景点类型列表
     */
    @GetMapping("list")
    public Result queryTypeList() {
        List<AttractionType> typeList = typeService
                .query().orderByAsc("sort").list();
        return Result.ok(typeList);
    }
}
