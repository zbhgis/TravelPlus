package com.travelplus.service;

import com.travelplus.dto.Result;

/**
 * <p>
 * 【浏览记录的实现】浏览记录服务接口：浏览游记/景点详情时写入 Redis ZSet，按时间倒序读取最近记录
 * </p>
 */
public interface IBrowseRecordService {

    /**
     * 记录一次浏览行为
     * @param userId 浏览者（登录用户）id
     * @param type   浏览对象类型：note=游记，attraction=景点
     * @param id     浏览对象id
     */
    void record(Long userId, String type, Long id);

    /**
     * 查询某用户最近浏览的记录列表
     * @param userId 用户id
     * @return Result.data = List&lt;Map&gt;：{type, id, title, image, time(score 时间戳)}，按浏览时间倒序
     */
    Result list(Long userId);
}
