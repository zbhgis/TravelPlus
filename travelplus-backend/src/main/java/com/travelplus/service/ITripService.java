package com.travelplus.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.travelplus.dto.Result;
import com.travelplus.dto.TripCreateDTO;
import com.travelplus.entity.Trip;

/**
 * 【旅行计划服务的实现】旅行计划服务接口：创建（三表落库）、当前用户分页列表、详情（归属校验 + 景点 JOIN）、删除（级联）
 * <p>
 * 实现细节：
 * - 全部接口需登录：/trip/** 未在 MvcConfig LoginInterceptor exclude 放行，未登录 401；
 *   Service 内再经 UserHolder 防御性判空，归属校验统一以 trip.userId vs UserHolder.getUser().id 为准。
 * - create 校验：title/destination 非空、endDate>=startDate、天数 1~30、每项 attractionId 必须存在；
 *   同一事务内插入 trip → days（dayIndex 从 1 递增）→ items（sort 从 0 递增）。
 * - delete 级联：先按 day_id IN 删 items，再按 trip_id 删 days，最后删 trip（@Transactional 保证原子性）。
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
public interface ITripService extends IService<Trip> {

    /**
     * 【旅行计划的实现】创建旅行计划（三表插入：trip + days + items）
     * <p>
     * 实现细节：
     * - userId 取自 UserHolder（不信任前端）；参数校验失败返回 fail。
     * - 每个 item 的 attractionId 经 AttractionMapper.selectById 校验存在，不存在 fail"景点不存在"。
     * - 插入顺序：先 save(trip) 回填 id → 逐天 insert TripDay（dayIndex=列表序+1）回填 id →
     *   逐条 insert TripDayItem（sort=列表序），整个过程在 @Transactional 事务内。
     * @param dto 请求体 {title,destination,startDate,endDate,days:[{title,items:[{attractionId,note}]}]}
     * @return Result.data=新计划 id
     */
    Result create(TripCreateDTO dto);

    /**
     * 【旅行计划的实现】当前登录用户的计划分页列表（按创建时间倒序）
     * <p>
     * 实现细节：tb_trip WHERE user_id=当前用户 ORDER BY create_time DESC 分页
     * （每页 SystemConstants.MAX_PAGE_SIZE），仅透出列表字段
     * {id,title,destination,startDate,endDate,createTime}，不含 days/items（详情接口承载）。
     * @param current 页码（默认第1页）
     * @return Result.data=List<Map>，total=计划总数
     */
    Result list(Integer current);

    /**
     * 【旅行计划的实现】计划详情（归属校验 + days/items 三级结构 + 景点 JOIN）
     * <p>
     * 实现细节：
     * - 归属校验：trip.userId != 当前登录用户 → fail"无权查看该计划"（他人计划不可见）。
     * - 组装：days 按 day_index 升序，items 按 sort 升序；item 经 AttractionMapper.selectById
     *   JOIN 取 name + images 首图（attractionName/attractionImage）；景点已删除则保留条目、
     *   attractionName 显示"景点已下架"、attractionImage 置 null。
     * @param id 计划id
     * @return Result.data=Map{trip字段..., days:[{id,dayIndex,title,items:[{id,attractionId,attractionName,attractionImage,note,sort}]}]}
     */
    Result detail(Long id);

    /**
     * 【旅行计划的实现】删除计划（仅创建者可删，级联删 days/items）
     * <p>
     * 实现细节：
     * - 归属校验：trip.userId != 当前登录用户 → fail"无权删除该计划"；重复删除走"计划不存在"幂等。
     * - 级联删除顺序：先查该计划全部 dayId → DELETE tb_trip_day_item WHERE day_id IN (...) →
     *   DELETE tb_trip_day WHERE trip_id=id → DELETE tb_trip WHERE id=id（@Transactional 保证原子性）。
     * @param id 计划id
     * @return 删除成功返回 ok
     */
    Result delete(Long id);

    /**
     * 【旅行计划的实现】向已有计划追加景点（旅游AI助手 add_to_trip 工具专用入口）
     * <p>
     * 实现细节：归属校验（仅创建者）→ 景点存在校验 → 按 dayIndex 定位日程（不存在时
     * 在当前最大 dayIndex 后顺延新建一天）→ sort=当天现有条目数（追加到末尾）。
     * @param tripId       计划id（必填）
     * @param dayIndex     第几天（可选，null/≤0 默认第 1 天；超出已有天数时顺延新建）
     * @param attractionId 景点id（必填）
     * @param note         安排备注（可选，空串兜底）
     * @return Result.data=实际写入的 dayIndex
     */
    Result addItem(Long tripId, Integer dayIndex, Long attractionId, String note);

}
