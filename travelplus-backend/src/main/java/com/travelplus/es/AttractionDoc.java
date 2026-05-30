package com.travelplus.es;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;

/**
 * 【Elasticsearch搜索的实现】景点搜索文档（ES 索引 attraction_es，从 tb_attraction 同步）。
 * <p>
 * 实现细节（分词策略取舍）：
 * - name 用 text 类型 + standard 标准分词：ES 8.x standard 分词器对中文按"字"切分（实测
 *   "压测景点1西湖景区1" → [压,测,景,点,1,西,湖,景,区,1]），配合 match_phrase 短语查询即可实现
 *   与 SQL LIKE '%keyword%' 等价的"连续子串命中"（西/湖 相邻成词组即命中），无需安装 IK 插件。
 * - 另加 keyword 子字段 name.keyword 支持精确匹配/聚合（搜索主路径用 name 的 match_phrase）。
 * - image 存逗号分隔图片的第 1 项（与 SearchServiceImpl.firstImage 同约定，同步时算好）。
 * - score/avgPrice 用 long：score 为 ×10 存储的整数评分（原值返回，前端 /10 展示），avgPrice 为整数均价。
 * - id 为雪花 Long：ES 用 long 存、搜索返回 Long，与旧 /search 返回类型完全一致（前端无精度问题）。
 * </p>
 *
 * @author TravelPlus
 */
@Data
@Document(indexName = "attraction_es")
public class AttractionDoc {

    /** 景点主键（雪花 Long） */
    @Id
    private Long id;

    /** 景点名称（text 标准分词 + keyword 子字段 name.keyword） */
    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "standard"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword))
    private String name;

    /** 首图路径（同步时取 images 逗号分隔第 1 项） */
    @Field(type = FieldType.Keyword)
    private String image;

    /** 评分 ×10 存储（原值返回） */
    @Field(type = FieldType.Long)
    private Long score;

    /** 均价（整数） */
    @Field(type = FieldType.Long)
    private Long avgPrice;

    /** 目的地城市（如 杭州/厦门，keyword 精确匹配） */
    @Field(type = FieldType.Keyword)
    private String destination;
}
