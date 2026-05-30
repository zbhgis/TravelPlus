package com.travelplus.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.travelplus.dto.Result;
import com.travelplus.dto.UserDTO;
import com.travelplus.entity.TravelNote;
import com.travelplus.service.ITravelNoteService;
import com.travelplus.utils.SystemConstants;
import com.travelplus.utils.UserHolder;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 【游记模块接口的实现】游记功能统一入口：发布、点赞、热门/详情/点赞榜、我的/他人/关注流列表、删除
 * <p>
 * 实现细节：
 * - 路由域：@RequestMapping("/note")，挂 /note/** 前缀。
 * - 鉴权：除 /note/hot 外，其余端点（/note 写、/note/of/me、/note/like/** 等）均被 LoginInterceptor
 *   拦截，未登录返回 401；作者/点赞用户身份由服务端从 UserHolder 获取。
 * - 点赞态用 Redis ZSet（note:liked:{id}）承载，详情/列表查询时由 isTravelNoteLiked 补充 isLike 字段；
 *   关注流（/note/of/follow）走 Redis Feed 收件箱 + 滚动分页，见各端点注释。
 *
 * @author TravelPlus
 */
@RestController
@RequestMapping("/note")
public class TravelNoteController {

    @Resource
    private ITravelNoteService noteService;

    /**
     * 【游记功能的实现】发布游记
     * <p>
     * 实现细节：
     * - 需登录：POST /note 被 LoginInterceptor 拦截；body {title, images(逗号分隔), content, attractionId?}，
     *   userId 由服务端从 UserHolder 写入，不信任前端传入。
     * - 保存成功后把 noteId 推送到该游记作者全部粉丝的 Feed 收件箱（Redis ZSet feed:{userId}，
     *   score=当前时间戳），实现"关注流"被动推送，见 service 实现注释。
     * - 返回 data=noteId，前端发布成功后可跳转详情页。
     * @param note 游记数据（JSON body，userId 由服务端填充）
     * @return data=游记id
     */
    @PostMapping
    public Result saveTravelNote(@RequestBody TravelNote note) {
        return noteService.saveTravelNote(note);
    }

    /**
     * 【点赞功能的实现】点赞/取消点赞切换（Redis ZSet 判重 + DB 计数同步）
     * <p>
     * 实现细节：
     * - 需登录：PUT /note/like/{id} 被 LoginInterceptor 拦截，未登录返回 401。
     * - 切换逻辑：查 note:liked:{id} 中当前用户 score 是否存在——不存在则 DB liked+1 并 ZADD 用户
     *   （score=时间戳，供点赞榜排序）；存在则 DB liked-1 并 ZREM。DB 与 Redis 同步更新保证计数一致。
     * - 返回 ok；是否已点赞由详情/列表接口的 isLike 字段回显。
     * @param id 游记id
     */
    @PutMapping("/like/{id}")
    public Result likeTravelNote(@PathVariable("id") Long id) {
        return noteService.likeTravelNote(id);
    }

    /**
     * 【游记功能的实现】查询当前登录用户的游记列表（分页）
     * <p>
     * 实现细节：
     * - 需登录：GET /note/of/me 被 LoginInterceptor 拦截，未登录返回 401。
     * - 直接从 UserHolder 取当前用户 id，按 user_id 等值分页查询（current 默认第 1 页，
     *   每页 SystemConstants.MAX_PAGE_SIZE 条），data=当前页游记列表。
     * - 走 MyBatis-Plus 分页插件，不做额外排序（按主键默认序），适合"我的作品"场景。
     * @param current 页码，默认第1页
     * @return data=当前页游记列表
     */
    @GetMapping("/of/me")
    public Result queryMyTravelNote(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        // 根据用户查询
        Page<TravelNote> page = noteService.query()
                .eq("user_id", user.getId()).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<TravelNote> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 【热门游记的实现】按点赞数倒序分页查询热门游记
     * <p>
     * 实现细节：
     * - 匿名可访问：/note/hot 在 MvcConfig 登录拦截器 exclude 列表放行（唯一放行的 note 接口），
     *   未登录也可浏览热门流。
     * - 按 liked 倒序分页（orderByDesc("liked")），每条记录补充作者信息（name/icon）与当前登录用户
     *   点赞态 isLike（未登录时 isLike 恒为 false），见 service 实现注释。
     * @param current 页码，默认第1页
     * @return data=当前页游记列表（含 name/icon/isLike）
     */
    @GetMapping("/hot")
    public Result queryHotTravelNote(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        return noteService.queryHotTravelNote(current);
    }

    /**
     * 【游记功能的实现】查询游记详情（含浏览记录）
     * <p>
     * 实现细节：
     * - 匿名可访问：GET /note/{id} 不在拦截器 exclude 列表，但 RefreshTokenInterceptor 对无 token
     *   请求放行且 UserHolder 为空，故游客可浏览；已登录用户浏览成功会写入浏览记录
     *   （Redis ZSet browse:{userId}，member=note:{id}），供 /user/browse/list 使用，见 service 注释。
     * - 游记不存在时 fail "游记不存在！"；成功时补充作者信息与当前用户点赞态 isLike。
     * @param id 游记id
     * @return data=游记详情
     */
    @GetMapping("/{id}")
    public Result queryTravelNoteById(@PathVariable("id") Long id) {
        return noteService.queryTravelNoteById(id);
    }

    /**
     * 【点赞排行榜的实现】查询某游记点赞数 Top5 的用户
     * <p>
     * 实现细节：
     * - 从 note:liked:{id} 的 Redis ZSet 取分数最高的前 5 个成员（score=点赞时间，先赞先排前），
     *   即 ZRANGE key 0 4；无点赞时返回空列表。
     * - 解析出用户 id 后一次 IN 查 tb_user 并 ORDER BY FIELD(id, ...) 保持 ZSet 顺序，
     *   转 UserDTO（id/nickName/icon）返回，供详情页"谁赞过"展示。
     * @param id 游记id
     * @return data=Top5 UserDTO 列表
     */
    @GetMapping("/likes/{id}")
    public Result queryTravelNoteLikes(@PathVariable("id") Long id) {
        return noteService.queryTravelNoteLikes(id);
    }

    /**
     * 【游记功能的实现】查询某用户的游记列表（分页，他人主页用）
     * <p>
     * 实现细节：
     * - 匿名可访问：GET /note/of/user 不在登录拦截器 exclude 列表但 RefreshTokenInterceptor 放行
     *   无 token 请求，游客也可查看他人游记；id 为目标用户 id（必填），current 默认第 1 页，
     *   每页 SystemConstants.MAX_PAGE_SIZE 条。
     * - 可见性（【笔记可见性的实现】）：仅返回该用户公开笔记（visible=0）或当前登录用户自己的
     *   笔记，条件 (visible = 0 OR user_id = 当前登录用户)——查看本人主页（id=自己）时全部可见，
     *   游客/他人查看时该用户仅自己可见的笔记（visible=1）不出现。
     * - 按 user_id 等值分页查询，data=当前页游记列表（不含作者冗余字段，与 /of/me 一致）。
     * @param current 页码，默认第1页
     * @param id      目标用户id
     * @return data=当前页游记列表
     */
    @GetMapping("/of/user")
    public Result queryTravelNoteByUserId(
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam("id") Long id) {
        // 【笔记可见性的实现】过滤条件：可见公开 或 本人（游客 currentUserId=null 仅公开）
        UserDTO currentUser = UserHolder.getUser();
        Long currentUserId = currentUser == null ? null : currentUser.getId();
        // 根据用户查询
        Page<TravelNote> page = noteService.query()
                .eq("user_id", id)
                .and(w -> {
                    w.eq("visible", 0);
                    if (currentUserId != null) {
                        w.or().eq("user_id", currentUserId);
                    }
                })
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<TravelNote> records = page.getRecords();
        return Result.ok(records);
    }

    /**
     * 【关注Feed的实现】分页查询我关注的所有人的可见游记（实时查询，按发布时间倒序）
     * <p>
     * 实现细节：
     * - 需登录：GET /note/of/follow 被 LoginInterceptor 拦截，未登录返回 401。
     * - 实时 join：服务端按"当前用户关注列表（tb_follow user_id=me）→ 这些用户发布的可见游记
     *   （visible=0）"实时查询，不再依赖 Redis Feed 收件箱推送——数据与我关注的人数实时一致
     *   （新发布/取关/隐藏即时反映），修复推送制下历史关注者游记缺失/不全的问题。
     *   前端已同步改为 current 分页调用。
     * - 分页：current 页码，每页 SystemConstants.MAX_PAGE_SIZE（10）条；
     *   data=List&lt;TravelNote&gt;（含 name/icon/isLike），total=符合条件的总数。
     * @param current 页码（从1开始）
     * @return data=当前页关注游记列表（含 name/icon/isLike），total=总数
     */
    @GetMapping("/of/follow")
    public Result queryTravelNoteOfFollow(
            @RequestParam("current") Integer current) {
        return noteService.queryTravelNoteOfFollow(current);
    }

    /**
     * 【笔记编辑的实现】编辑已发布笔记（仅作者本人，非空字段更新）
     * <p>
     * 实现细节：
     * - 需登录：PUT /note/{id} 在 LoginInterceptor 拦截范围内（/note/** 除 /note/hot 外均需登录），
     *   未登录返回 401；作者校验在 service 内用 UserHolder 完成，非作者 fail"无权编辑该游记"。
     * - body 接收 TravelNote 部分字段（title/content/images/attractionId/visible 均可选，null=不修改），
     *   详见 TravelNoteServiceImpl.updateNote 注释。
     * @param id   待编辑的游记id
     * @param note 编辑入参（JSON body，部分字段可选）
     * @return 编辑成功返回 ok
     */
    @PutMapping("/{id}")
    public Result updateNote(@PathVariable("id") Long id, @RequestBody TravelNote note) {
        return noteService.updateNote(id, note);
    }

    /**
     * 【游记删除的实现】删除指定游记（仅作者本人可删）
     * <p>
     * 实现细节：
     * - DELETE /note/{id} 会被 LoginInterceptor 拦截（/note/** 除 /note/hot 外均需登录），
     *   未登录返回 401；作者校验在 service 内用 UserHolder 完成，非作者 fail"无权删除该游记"。
     * - 删除同时清理 Redis：note:liked:{id} 点赞集合 + 作者自身 Feed 中的 noteId，见 service 实现注释。
     * @param id 游记id
     */
    @DeleteMapping("/{id}")
    public Result deleteNote(@PathVariable("id") Long id) {
        return noteService.deleteNote(id);
    }
}
