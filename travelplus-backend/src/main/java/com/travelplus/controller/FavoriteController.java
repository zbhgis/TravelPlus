package com.travelplus.controller;

import com.travelplus.dto.Result;
import com.travelplus.service.IFavoriteService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.Map;

/**
 * 【收藏功能的实现】收藏模块接口入口：收藏/取消收藏（游记/景点）、收藏列表、收藏状态、收藏数
 * <p>
 * 实现细节：
 * - 路由域：@RequestMapping("/favorite")，挂 /favorite/** 前缀；不在 MvcConfig exclude 放行列表，
 *   LoginInterceptor 拦截（收藏是登录用户的私有行为），未登录访问返回 401。
 * - 收藏/取消收藏：POST /favorite body {targetType: note|attraction, targetId}；DELETE /favorite/{targetType}/{targetId}。
 *   收藏走唯一约束幂等（重复收藏返回成功，见 FavoriteServiceImpl.favorite 注释）。
 * - 收藏列表：GET /favorite/list?type=note|attraction&current=&userId=（userId 可选：不传=当前登录用户，
 *   传入=查看该用户收藏，供他人主页展示），data=List<Map{id, targetType, targetId, title, image, createTime}>。
 * - 状态/计数：GET /favorite/status?type=&id=（需登录，判断当前用户是否已收藏）；
 *   GET /favorite/count?type=&id=（目标收藏总数，公开供详情页展示）。
 *
 * @author TravelPlus
 * @since 2026-08-07
 */
@RestController
@RequestMapping("/favorite")
public class FavoriteController {

    @Resource
    private IFavoriteService favoriteService;

    /**
     * 【收藏功能的实现】收藏游记/景点（幂等）
     * <p>
     * 实现细节：
     * - 需登录：POST /favorite 被 LoginInterceptor 拦截，未登录返回 401。
     * - body {targetType, targetId}：targetType=note（游记）/ attraction（景点）；targetId 目标id。
     * - 幂等：唯一约束 uk_user_target(user_id, target_type, target_id) 冲突=已收藏，
     *   服务端捕获后返回 ok，重复收藏不报错不重复发通知。
     * - 首次收藏成功会旁路通知目标作者（note 有作者；attraction 无作者跳过），通知 type=favorite。
     * @param body 请求体 {targetType: String, targetId: Number}
     * @return 收藏成功返回 ok（重复收藏同样 ok）
     */
    @PostMapping
    public Result favorite(@RequestBody Map<String, Object> body) {
        String targetType = body.get("targetType") == null ? null : body.get("targetType").toString();
        Long targetId = body.get("targetId") == null ? null : Long.valueOf(body.get("targetId").toString());
        return favoriteService.favorite(targetType, targetId);
    }

    /**
     * 【收藏功能的实现】取消收藏游记/景点（幂等）
     * <p>
     * 实现细节：
     * - 需登录：DELETE /favorite/{targetType}/{targetId} 被 LoginInterceptor 拦截。
     * - 按 (user_id, target_type, target_id) 条件删除，未收藏时删 0 行同样返回 ok（天然幂等）。
     * @param targetType 目标类型：note / attraction
     * @param targetId   目标id
     * @return 取消成功返回 ok
     */
    @DeleteMapping("/{targetType}/{targetId}")
    public Result unfavorite(@PathVariable("targetType") String targetType,
                             @PathVariable("targetId") Long targetId) {
        return favoriteService.unfavorite(targetType, targetId);
    }

    /**
     * 【收藏功能的实现】某用户某类型收藏列表（分页，join 目标标题与首图；默认当前登录用户）
     * <p>
     * 实现细节：
     * - 需登录：GET /favorite/list?type=&current= 被 LoginInterceptor 拦截。
     * - 归属用户：userId 参数可选——缺省（null）=当前登录用户（本人"我的收藏"页）；
     *   userId 非空=查看该用户收藏（他人主页收藏 tab 展示），此时校验该用户存在（不存在 fail"用户不存在"）。
     * - type=note 返回游记收藏（title=游记标题），type=attraction 返回景点收藏（title=景点名称）。
     * - 按 create_time 倒序分页（每页 SystemConstants.MAX_PAGE_SIZE 条）；每条含
     *   {id, targetType, targetId, title, image, createTime}，image=目标 images 首图；目标已删除跳过。
     * @param type    收藏类型：note / attraction（必填）
     * @param current 页码，默认第1页
     * @param userId  收藏归属用户id（可选；不传=当前登录用户本人，传入=查看该用户收藏）
     * @return data=List<Map>，total=收藏总数
     */
    @GetMapping("/list")
    public Result list(@RequestParam("type") String type,
                       @RequestParam(value = "current", defaultValue = "1") Integer current,
                       @RequestParam(value = "userId", required = false) Long userId) {
        return favoriteService.list(type, current, userId);
    }

    /**
     * 【收藏功能的实现】当前用户是否已收藏该目标（详情页收藏按钮回显）
     * <p>
     * 实现细节：
     * - 需登录：GET /favorite/status?type=&id= 被 LoginInterceptor 拦截，未登录返回 401。
     * - 直接读 DB COUNT 判断，与收藏/取消收藏实时一致；data=boolean。
     * @param type 目标类型：note / attraction（必填）
     * @param id   目标id（必填）
     * @return data=boolean 是否已收藏
     */
    @GetMapping("/status")
    public Result status(@RequestParam("type") String type,
                         @RequestParam("id") Long id) {
        return favoriteService.status(type, id);
    }

    /**
     * 【收藏功能的实现】目标收藏数（详情页收藏数展示，公开）
     * <p>
     * 实现细节：GET /favorite/count?type=&id= 不做登录限制（与 /attraction/{id}、/note/{id}
     * 同策略，游客可浏览收藏数）；data=收藏数。
     * @param type 目标类型：note / attraction（必填）
     * @param id   目标id（必填）
     * @return data=收藏数
     */
    @GetMapping("/count")
    public Result count(@RequestParam("type") String type,
                        @RequestParam("id") Long id) {
        return favoriteService.favoriteCount(type, id);
    }

}
