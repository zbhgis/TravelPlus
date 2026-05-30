package com.travelplus.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.travelplus.dto.Result;
import com.travelplus.dto.TripCreateDTO;
import com.travelplus.dto.UserDTO;
import com.travelplus.entity.Attraction;
import com.travelplus.entity.Trip;
import com.travelplus.entity.TripDay;
import com.travelplus.entity.TripDayItem;
import com.travelplus.mapper.AttractionMapper;
import com.travelplus.mapper.TripDayItemMapper;
import com.travelplus.mapper.TripDayMapper;
import com.travelplus.mapper.TripMapper;
import com.travelplus.service.ITripService;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 【旅行计划的实现】旅行计划服务实现：创建（三表插入）、当前用户分页列表、详情（归属校验 + 景点 JOIN）、级联删除
 * <p>
 * 实现细节：
 * - 三表关联：tb_trip（计划）→ tb_trip_day（日程，trip_id）→ tb_trip_day_item（景点安排，day_id）。
 * - 归属校验：全部写操作先取 UserHolder.getUser()，再比对 trip.userId，非本人一律 fail（防御越权）。
 * - 景点信息不冗余存储：详情/地图数据实时经 AttractionMapper JOIN tb_attraction 取 name + images 首图。
 * - 直接注入 TripDayMapper/TripDayItemMapper/AttractionMapper 而非上层 Service，
 *   避免 IAttractionService 实现类注入 IBrowseRecordService 等带来的循环依赖风险。
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@Slf4j
@Service
public class TripServiceImpl extends ServiceImpl<TripMapper, Trip> implements ITripService {

    /** 单计划允许的最大天数（create 校验上限） */
    private static final int MAX_TRIP_DAYS = 30;

    @Resource
    private TripDayMapper tripDayMapper;

    @Resource
    private TripDayItemMapper tripDayItemMapper;

    @Resource
    private AttractionMapper attractionMapper;

    /**
     * 【旅行计划的实现】创建旅行计划（三表插入：trip + days + items）
     * <p>
     * 实现细节：
     * 1) 参数校验顺序：登录态 → title 非空 → destination 非空 → startDate/endDate 非空且 end>=start →
     *    天数 1~30 → 逐 item 校验 attractionId 存在（selectById 判空）。
     * 2) 落库顺序（同一事务）：save(trip) 回填 id → 按 days 顺序逐天 insert TripDay
     *    （dayIndex=列表序+1，title 空串兜底）回填 id → 逐条 insert TripDayItem
     *    （dayId=当天日程 id，sort=列表序，note 空串兜底）。
     * 3) userId 取 UserHolder 当前用户，不信任前端；@Transactional 保证中途失败整体回滚。
     *
     * @param dto 请求体 {title,destination,startDate,endDate,days:[{title,items:[{attractionId,note}]}]}
     * @return Result.data=新计划 id
     */
    @Override
    @Transactional
    public Result create(TripCreateDTO dto) {
        // 1.登录态（/trip/** 已被 LoginInterceptor 拦截，这里防御性兜底）
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        // 2.基础字段校验
        if (StrUtil.isBlank(dto.getTitle())) {
            return Result.fail("计划标题不能为空");
        }
        if (StrUtil.isBlank(dto.getDestination())) {
            return Result.fail("目的地不能为空");
        }
        if (dto.getStartDate() == null || dto.getEndDate() == null) {
            return Result.fail("出行日期不能为空");
        }
        if (dto.getEndDate().isBefore(dto.getStartDate())) {
            return Result.fail("结束日期不能早于开始日期");
        }
        // 3.天数校验：1~30 天
        List<TripCreateDTO.TripDayDTO> days = dto.getDays();
        if (days == null || days.isEmpty() || days.size() > MAX_TRIP_DAYS) {
            return Result.fail("行程天数需在1~30天之间");
        }
        // 4.逐条校验景点安排：attractionId 必须存在
        for (TripCreateDTO.TripDayDTO dayDTO : days) {
            if (dayDTO.getItems() == null) {
                continue;
            }
            for (TripCreateDTO.TripItemDTO itemDTO : dayDTO.getItems()) {
                if (itemDTO.getAttractionId() == null
                        || attractionMapper.selectById(itemDTO.getAttractionId()) == null) {
                    return Result.fail("景点不存在");
                }
            }
        }
        // 5.插入计划主表
        Trip trip = new Trip();
        trip.setUserId(currentUser.getId());
        trip.setTitle(dto.getTitle());
        trip.setDestination(dto.getDestination());
        trip.setStartDate(dto.getStartDate());
        trip.setEndDate(dto.getEndDate());
        save(trip);
        // 6.插入日程 + 景点安排（dayIndex/sort 服务端按序生成，不信任前端）
        for (int i = 0; i < days.size(); i++) {
            TripCreateDTO.TripDayDTO dayDTO = days.get(i);
            TripDay day = new TripDay();
            day.setTripId(trip.getId());
            day.setDayIndex(i + 1);
            day.setTitle(StrUtil.isBlank(dayDTO.getTitle()) ? "" : dayDTO.getTitle());
            tripDayMapper.insert(day);
            List<TripCreateDTO.TripItemDTO> items = dayDTO.getItems();
            if (items == null) {
                continue;
            }
            for (int j = 0; j < items.size(); j++) {
                TripCreateDTO.TripItemDTO itemDTO = items.get(j);
                TripDayItem item = new TripDayItem();
                item.setDayId(day.getId());
                item.setAttractionId(itemDTO.getAttractionId());
                item.setNote(StrUtil.isBlank(itemDTO.getNote()) ? "" : itemDTO.getNote());
                item.setSort(j);
                tripDayItemMapper.insert(item);
            }
        }
        return Result.ok(trip.getId());
    }

    /**
     * 【旅行计划的实现】当前登录用户的计划分页列表（按创建时间倒序）
     * <p>
     * 实现细节：query().eq("user_id", 当前用户).orderByDesc("create_time") 分页
     * （每页 SystemConstants.MAX_PAGE_SIZE 条），仅透出列表字段 Map{id,title,destination,
     * startDate,endDate,createTime}；days/items 不在此接口返回（体量小、由详情承载），
     * 避免列表页冗余查询。
     *
     * @param current 页码（默认第1页）
     * @return Result.data=List<Map>，total=计划总数
     */
    @Override
    public Result list(Integer current) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        Page<Trip> page = query()
                .eq("user_id", currentUser.getId())
                .orderByDesc("create_time")
                .page(new Page<>(current == null ? 1 : current, SystemConstants.MAX_PAGE_SIZE));
        List<Map<String, Object>> records = new ArrayList<>();
        for (Trip trip : page.getRecords()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", trip.getId());
            m.put("title", trip.getTitle());
            m.put("destination", trip.getDestination());
            m.put("startDate", trip.getStartDate());
            m.put("endDate", trip.getEndDate());
            m.put("createTime", trip.getCreateTime());
            records.add(m);
        }
        return Result.ok(records, page.getTotal());
    }

    /**
     * 【旅行计划的实现】计划详情（归属校验 + days/items 三级结构 + 景点 JOIN）
     * <p>
     * 实现细节：
     * 1) 归属校验：trip 不存在 fail"计划不存在"；trip.userId != 当前用户 fail"无权查看该计划"。
     * 2) 组装：days 按 day_index 升序（QueryWrapper.orderByAsc），items 按 sort 升序。
     * 3) 景点 JOIN：item 经 attractionMapper.selectById 取 name + images 首图（firstImage）；
     *    景点已删除时保留条目、attractionName="景点已下架"、attractionImage=null
     *    （让前端仍能展示该安排，仅提示下架）。
     * 4) 返回 Map：顶层 {id,title,destination,startDate,endDate,createTime,days}，
     *    day= {id,dayIndex,title,items}，item= {id,attractionId,attractionName,attractionImage,note,sort}。
     *
     * @param id 计划id
     * @return Result.data=Map{trip字段..., days:[...]}
     */
    @Override
    public Result detail(Long id) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        Trip trip = getById(id);
        if (trip == null) {
            return Result.fail("计划不存在");
        }
        // 归属校验：仅创建者本人可见
        if (!trip.getUserId().equals(currentUser.getId())) {
            return Result.fail("无权查看该计划");
        }
        // 查日程（按 day_index 升序）
        List<TripDay> days = tripDayMapper.selectList(
                new QueryWrapper<TripDay>().eq("trip_id", id).orderByAsc("day_index"));
        List<Map<String, Object>> dayMaps = new ArrayList<>();
        for (TripDay day : days) {
            Map<String, Object> dayMap = new LinkedHashMap<>();
            dayMap.put("id", day.getId());
            dayMap.put("dayIndex", day.getDayIndex());
            dayMap.put("title", day.getTitle());
            // 查该日程的景点安排（按 sort 升序）并 JOIN 景点信息
            List<TripDayItem> items = tripDayItemMapper.selectList(
                    new QueryWrapper<TripDayItem>().eq("day_id", day.getId()).orderByAsc("sort"));
            List<Map<String, Object>> itemMaps = new ArrayList<>();
            for (TripDayItem item : items) {
                Map<String, Object> itemMap = new LinkedHashMap<>();
                itemMap.put("id", item.getId());
                itemMap.put("attractionId", item.getAttractionId());
                Attraction attraction = attractionMapper.selectById(item.getAttractionId());
                if (attraction == null) {
                    // 景点已删除：保留条目并占位提示，避免用户计划数据凭空消失
                    itemMap.put("attractionName", "景点已下架");
                    itemMap.put("attractionImage", null);
                } else {
                    itemMap.put("attractionName", attraction.getName());
                    itemMap.put("attractionImage", firstImage(attraction.getImages()));
                }
                itemMap.put("note", item.getNote());
                itemMap.put("sort", item.getSort());
                itemMaps.add(itemMap);
            }
            dayMap.put("items", itemMaps);
            dayMaps.add(dayMap);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", trip.getId());
        result.put("title", trip.getTitle());
        result.put("destination", trip.getDestination());
        result.put("startDate", trip.getStartDate());
        result.put("endDate", trip.getEndDate());
        result.put("createTime", trip.getCreateTime());
        result.put("days", dayMaps);
        return Result.ok(result);
    }

    /**
     * 【旅行计划的实现】删除计划（仅创建者可删，级联删 days/items）
     * <p>
     * 实现细节：
     * 1) 归属校验：trip 不存在 fail"计划不存在"（重复删除幂等）；trip.userId != 当前用户
     *    fail"无权删除该计划"。
     * 2) 级联删除顺序（@Transactional 保证原子性）：
     *    a. 先查该计划全部 dayId；
     *    b. DELETE tb_trip_day_item WHERE day_id IN (dayIds)；
     *    c. DELETE tb_trip_day WHERE trip_id=id；
     *    d. DELETE tb_trip WHERE id=id（removeById）。
     *    顺序不可颠倒——item 依赖 day_id、day 依赖 trip_id，外键语义下先删最内层。
     *
     * @param id 计划id
     * @return 删除成功返回 ok
     */
    @Override
    @Transactional
    public Result delete(Long id) {
        UserDTO currentUser = UserHolder.getUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }
        Trip trip = getById(id);
        if (trip == null) {
            return Result.fail("计划不存在");
        }
        if (!trip.getUserId().equals(currentUser.getId())) {
            return Result.fail("无权删除该计划");
        }
        // 1.先收集该计划全部日程 id（级联删除最内层用）
        List<TripDay> days = tripDayMapper.selectList(new QueryWrapper<TripDay>().eq("trip_id", id));
        List<Long> dayIds = days.stream().map(TripDay::getId).collect(Collectors.toList());
        // 2.先删景点安排（day_id IN 条件删）
        if (!dayIds.isEmpty()) {
            tripDayItemMapper.delete(new QueryWrapper<TripDayItem>().in("day_id", dayIds));
        }
        // 3.再删日程（trip_id 条件删）
        tripDayMapper.delete(new QueryWrapper<TripDay>().eq("trip_id", id));
        // 4.最后删计划主表
        removeById(id);
        return Result.ok();
    }

    /**
     * 【地图数据/景点 JOIN 的实现】取景点 images 逗号分隔列表的首图
     * <p>
     * 实现细节：images 形如 "/imgs/attraction/1/0.jpg,/imgs/attraction/1/1.jpg"，
     * 按 "," 切分取第一个非空段；全空返回 null。供详情 JOIN 与地图点位 image 字段复用。
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
