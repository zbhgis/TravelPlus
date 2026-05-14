package com.travelplus.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelplus.dto.Result;
import com.travelplus.entity.Attraction;
import com.travelplus.limiter.annotation.RateLimiter;
import com.travelplus.service.IAttractionService;
import com.travelplus.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author TravelPlus
 */
@RestController
@RequestMapping("/attraction")
public class AttractionController {

    @Resource
    public IAttractionService attractionService;

    /**
     * 根据id查询景点信息
     * @param id 景点id
     * @return 景点详情数据
     */
    @GetMapping("/{id}")
    public Result queryAttractionById(@PathVariable("id") Long id) {
        return attractionService.queryById(id);
    }

    /**
     * 新增景点信息
     * @param attraction 景点数据
     * @return 景点id
     */
    @PostMapping
    public Result saveAttraction(@RequestBody Attraction attraction) {
        // 写入数据库
        attractionService.save(attraction);
        // 返回景点id
        return Result.ok(attraction.getId());
    }

    /**
     * 更新景点信息
     * @param attraction 景点数据
     * @return 无
     */
    @PutMapping
    public Result updateAttraction(@RequestBody Attraction attraction) {
        // 写入数据库
        return attractionService.update(attraction);
    }

    /**
     * 【滑动窗口限流】-4
     * 根据景点类型分页查询景点信息
     * 举例测试滑动窗口限流，这里设置的参数的意思是 在window=5秒窗口时间内，限制访问次数是limit=5次，若5秒内超过5次则会限流(即限制访问)
     * 测试：  http://localhost:8081/attraction/of/type?typeId=1
     * @param typeId 景点类型
     * @param current 页码
     * @return 景点列表
     */
    @RateLimiter(
            key = "ticket:seckill:",
            window = 5,
            limit = 5,
            message = "查询次数过多，请稍后再试",
            type = RateLimiter.LimitType.METHOD
    )
    @GetMapping("/of/type")
    public Result queryAttractionByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y) {
       return attractionService.queryAttractionByType(typeId, current, x, y);
    }

    /**
     * 根据景点名称关键字分页查询景点信息
     * @param name 景点名称关键字
     * @param current 页码
     * @return 景点列表
     */
    @GetMapping("/of/name")
    public Result queryAttractionByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        // 根据类型分页查询
        Page<Attraction> page = attractionService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 返回数据
        return Result.ok(page.getRecords());
    }
}
