package com.travelplus.service;

import java.util.Map;

/**
 * 【Elasticsearch搜索的实现】搜索索引数据同步接口：负责把 MySQL 数据全量/单条同步到 ES 三个索引。
 * <p>
 * 设计说明：
 * - fullRebuild：全量重建（删索引重建映射 + 分页批量 bulk 同步 22 万级数据），供启动初始化和手动触发接口调用。
 * - indexAttraction/Note/User(id)：单条同步（后续业务写库后调用，本次为可选能力，保证增量一致性扩展点）。
 * - 实现见 {@link com.travelplus.service.impl.SearchIndexServiceImpl}。
 * </p>
 *
 * @author TravelPlus
 */
public interface ISearchIndexService {

    /**
     * 【Elasticsearch搜索的实现】全量重建三个搜索索引（attraction_es/note_es/user_es）。
     * <p>
     * 实现细节：
     * - 先删旧索引再按 @Document 注解重建映射（保证文档结构始终与当前实体字段一致）；
     * - 三张表分别分页查询（每批 1000，MyBatis-Plus selectPage + stream 转换文档）后 bulk 写入；
     * - 游记需批量查作者昵称冗余进 name 字段；返回每类同步计数。
     * </p>
     * @return Map&lt;索引类型, 同步条数&gt;（attraction/note/user）
     */
    Map<String, Long> fullRebuild();

    /**
     * 【Elasticsearch搜索的实现】单条同步景点到 ES（后续业务新增/更新景点后调用，可选）
     * @param id 景点 id
     */
    void indexAttraction(Long id);

    /**
     * 【Elasticsearch搜索的实现】单条同步游记到 ES（后续业务新增/更新游记后调用，可选）
     * @param id 游记 id
     */
    void indexNote(Long id);

    /**
     * 【Elasticsearch搜索的实现】单条同步用户到 ES（后续业务新增/更新用户后调用，可选）
     * @param id 用户 id
     */
    void indexUser(Long id);
}
