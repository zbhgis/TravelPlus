package com.travelplus.controller;

import cn.hutool.core.util.StrUtil;
import com.travelplus.dto.Result;
import com.travelplus.entity.Attraction;
import com.travelplus.mapper.AttractionMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 【地图数据的实现】地图数据接口：全量景点坐标点，供前端地图标记
 * <p>
 * 实现细节：
 * - 数据来源：直接复用现有 tb_attraction 的经纬度字段（x 经度 / y 纬度），不新增地图表、
 *   不改景点表结构；点位与景点详情天然一致（id/typeId 可跳转详情、按类型着色）。
 * - 匿名可访问：/map/points 已加入 MvcConfig LoginInterceptor exclude 放行列表，无需 token
 *   （地图加载先于登录页，公开数据）。
 * - null 过滤与补全策略：坐标来自既有数据，运行库 20 个景点均已含杭州及周边真实坐标
 *   （种子脚本同源），接口层再对 x/y 为 null 的记录兜底过滤，保证地图标记点坐标合法。
 * - 字段说明：image 取 images 逗号分隔首图；score 为 ×10 存储的整数（与 /attraction/** 契约一致），
 *   前端展示时 ÷10 处理，地图气泡/评分标签直接沿用该约定。
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@RestController
@RequestMapping("/map")
public class MapController {

    @Resource
    private AttractionMapper attractionMapper;

    /**
     * 【地图数据的实现】查询全部景点坐标点（公开接口，无需登录）
     * <p>
     * 实现细节：
     * - attractionMapper.selectList(null) 取 tb_attraction 全量（demo 规模 20 条，全量读取可接受；
     *   生产应改分页/按可视区域裁剪，注释保留说明）。
     * - 过滤 x/y 为 null 的记录（缺坐标无法落点，兜底处理）；逐条组装
     *   Map{id,name,x,y,typeId,image,score} 返回，前端据此打标记点。
     * @return data=List<Map{id,name,x,y,typeId,image,score}>（不含坐标缺失记录）
     */
    @GetMapping("/points")
    public Result queryMapPoints() {
        List<Attraction> all = attractionMapper.selectList(null);
        List<Map<String, Object>> points = new ArrayList<>();
        for (Attraction attraction : all) {
            // 坐标缺失的记录无法地图落点，过滤不返回
            if (attraction.getX() == null || attraction.getY() == null) {
                continue;
            }
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("id", attraction.getId());
            point.put("name", attraction.getName());
            point.put("x", attraction.getX());
            point.put("y", attraction.getY());
            point.put("typeId", attraction.getTypeId());
            point.put("image", firstImage(attraction.getImages()));
            point.put("score", attraction.getScore());
            points.add(point);
        }
        return Result.ok(points);
    }

    /**
     * 【地图数据的实现】取景点 images 逗号分隔列表的首图（地图点位缩略图）
     * <p>
     * 实现细节：images 形如 "/imgs/attraction/1/0.jpg,/imgs/attraction/1/1.jpg"，
     * 按 "," 切分取第一个非空段；全空返回 null（前端可兜底占位图）。
     */
    private String firstImage(String images) {
        if (StrUtil.isBlank(images)) {
            return null;
        }
        for (String img : images.split(",")) {
            if (StrUtil.isNotBlank(img)) {
                return img;
            }
        }
        return null;
    }
}
