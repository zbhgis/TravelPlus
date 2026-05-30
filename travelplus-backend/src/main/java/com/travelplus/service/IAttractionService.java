package com.travelplus.service;

import com.travelplus.dto.Result;
import com.travelplus.entity.Attraction;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * 【景点服务的实现】景点服务接口：详情查询（缓存防穿透/防击穿）、点赞/取消点赞、更新（更新后删缓存）、按类型分页查询（Geo 距离排序）
 * <p>
 * 实现细节：
 * - 详情查询走 CacheClient 缓存（当前启用 queryWithPassThrough 穿透方案，互斥锁/逻辑过期方案保留备选），
 *   登录用户浏览时同步写浏览记录（见 impl 注释）。
 * - 点赞仿游记点赞（见 impl 注释）：Redis ZSet attraction:liked:{id} 承载赞过集合，
 *   与游记不同——景点无 liked 计数列，不做 DB 计数同步，赞过仅列表用途。
 * - 按类型查询支持"无坐标=DB 分页、带坐标=Redis GEO 按距离排序分页"两种模式，入口限流见 Controller。
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
public interface IAttractionService extends IService<Attraction> {

    /**
     * 【景点详情缓存查询的实现】查询景点详情：缓存防穿透 + 登录用户浏览记录
     * <p>
     * 实现细节：cacheClient.queryWithPassThrough 缓存空值防穿透（互斥锁/逻辑过期方案注释保留备选），
     * 查询成功后对登录用户写浏览记录（member=attraction:{id}，ZSet score=时间戳）。
     * @param id 景点id
     * @return 景点详情；不存在 fail "景点不存在！"
     */
    Result queryById(Long id);

    /**
     * 【景点点赞的实现】景点点赞/取消点赞切换（仿游记点赞，无 DB 计数）
     * <p>
     * 实现细节：以 Redis ZSet attraction:liked:{id}（ATTRACTION_LIKED_KEY）中是否含当前用户判断状态——
     * 未点赞则 ZADD 用户（member=userId，score=System.currentTimeMillis() 点赞时间戳，供赞过列表倒序）；
     * 已点赞则 ZREM 移除（再次点击=取消）。景点表 tb_attraction 无 liked 计数列，因此不做 DB
     * liked ±1（与游记点赞唯一差异），赞过判定/赞过列表全走 Redis ZSet，详见 impl 注释。
     * @param id 景点id
     * @return 切换成功返回 ok
     */
    Result likeAttraction(Long id);

    /**
     * 【景点更新的实现】更新景点后删除缓存
     * <p>
     * 实现细节：先 updateById 落库（@Transactional），再删除 Redis key=cache:attraction:{id}，
     * 保证下次查询重新加载新数据（Cache Aside 模式）。
     * @param attraction 待更新的景点（id 必填）
     * @return 更新成功返回 ok；id 为空 fail
     */
    Result update(Attraction attraction);


    /**
     * 【景点按类型查询的实现】按景点类型分页查询，支持按坐标距离排序
     * <p>
     * 实现细节：x/y 任一为空走 DB 分页（Page + DEFAULT_PAGE_SIZE）；均非空时走 Redis GEO
     * （GEOSEARCH BYLONLAT 半径 5000m + WITHDISTANCE）按距离升序取 end 条再截取 from~end，
     * 最后 IN 查 DB + ORDER BY FIELD 保持距离顺序并回填 distance 字段；入口限流由
     * Controller 层 @RateLimiter 注解实现（5 秒窗口限 5 次，超限 fail "查询次数过多"）。
     * @param typeId  景点类型id
     * @param current 页码（从1开始）
     * @param x       经度（可选，与 y 一起提供时走 GEO 距离排序）
     * @param y       纬度（可选）
     * @return 景点列表；无数据返回空列表
     */
    Result queryAttractionByType(Integer typeId, Integer current, Double x, Double y);

    /**
     * 【动态评分的实现】用户给景点打分（1~5），唯一约束 upsert——先查该用户是否已打分，未打分则插入，
     * 已打分则修改（用户可改分）；一人一票由 uk_ar(user_id, attraction_id) 唯一键保证。
     * <p>
     * 实现细节：需登录（service 内 UserHolder 判空兜底，见 impl 注释）；score 非 1~5 整数 fail；
     * 景点不存在 fail"景点不存在"；打分只影响 tb_attraction_rating，动态均分由查询时 AVG 计算。
     * @param attractionId 景点id
     * @param score        评分 1~5
     * @return 打分成功返回 ok
     */
    Result rate(Long attractionId, Integer score);

    /**
     * 【动态评分的实现】查询某用户对某景点的打分（详情 myRating 的独立查询入口，可选接口）
     * <p>
     * 实现细节：需登录（/attraction/rate/status 落入拦截范围）；attractionId 必填，userId 可选——
     * 缺省取当前登录用户；返回 data=1~5 整数，未打分返回 ok 且 data 为 null。
     * @param attractionId 景点id
     * @param userId       用户id（可选，缺省=当前登录用户）
     * @return data=打分 1~5；未打分为 null
     */
    Result rateStatus(Long attractionId, Long userId);

    /**
     * 【打卡与足迹的实现】用户打卡某景点（唯一约束幂等：一人一景点只打卡一次）
     * <p>
     * 实现细节：需登录（service 内 UserHolder 判空兜底）；景点不存在 fail"景点不存在"；
     * 已打卡返回 ok 不重复计数（uk_ac(user_id, attraction_id) 唯一键 + 先查判空），
     * 打卡记录即足迹来源，来过统计=打卡 COUNT（见 queryById 的 checkinCount）。
     * @param id 景点id
     * @return 打卡成功（或已打卡）返回 ok
     */
    Result checkin(Long id);

    /**
     * 【城市推荐景点的实现】去重目的地城市列表（公开，按该城市景点数倒序，相同数量按名称升序）
     * <p>
     * 实现细节：按 tb_attraction.destination 分组统计景点数排序，返回 List&lt;String&gt; 城市名；
     * 无数据返回空列表。
     * @return 城市列表
     */
    Result queryDestinations();

    /**
     * 【城市推荐景点的实现】按目的地城市分页查询景点列表（公开，按 destination 列等值筛选）
     * <p>
     * 实现细节：destination = city 等值分页（每页 MAX_PAGE_SIZE），data=List&lt;Attraction&gt;（Map 或实体，
     * 与 /attraction/of/type 返回结构一致）；无数据返回空列表。
     * @param city    目的地城市（必填）
     * @param current 页码，默认第1页
     * @return 该城市景点列表
     */
    Result queryByDestination(String city, Integer current);

    /**
     * 【景点按名称查询的实现】按景点名称关键字分页查询（公开，/attraction/of/name 委托入口）
     * <p>
     * 实现细节：name 非空按 name LIKE 模糊匹配，name 为空则全量分页（每页 MAX_PAGE_SIZE 条）；
     * 返回前经 attachListInfo 批量补充动态评分/打卡数/门票信息（avgScore 与详情同源），
     * 保证列表主页卡片与详情评分一致（修复原"列表用原 score、详情用 avgScore 不一致"bug）。
     * @param name    景点名称关键字（可选，空则查全部）
     * @param current 页码，默认第1页
     * @return 景点列表
     */
    Result queryAttractionByName(String name, Integer current);
}
