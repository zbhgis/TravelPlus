package com.travelplus.es;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;

/**
 * 【Elasticsearch搜索的实现】游记搜索文档（ES 索引 note_es，从 tb_travel_note 同步）。
 * <p>
 * 实现细节（分词策略取舍）：
 * - title 用 text + standard 标准分词（中文按字切分，match_phrase 实现 LIKE 等价连续子串命中，不装 IK）。
 * - userId/name 冗余存储作者 id 与昵称（同步时批量查 tb_user 组装，避免搜索时 N+1 查作者）。
 * - visible 冗余存储笔记可见性（0=公开）：ES 查询时用 bool filter 复刻 SQL 的
 *   "visible=0 OR user_id=当前用户" 过滤，保证他人隐藏笔记不出现在搜索结果（行为与 LIKE 实现一致）。
 * - image 存逗号分隔图片的第 1 项（同步时算好）。
 * </p>
 *
 * @author TravelPlus
 */
@Data
@Document(indexName = "note_es")
public class NoteDoc {

    /** 游记主键（雪花 Long） */
    @Id
    private Long id;

    /** 游记标题（text 标准分词 + keyword 子字段 title.keyword） */
    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "standard"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword))
    private String title;

    /** 首图路径（同步时取 images 逗号分隔第 1 项） */
    @Field(type = FieldType.Keyword)
    private String image;

    /** 作者用户 id */
    @Field(type = FieldType.Long)
    private Long userId;

    /** 作者昵称（同步时批量查 tb_user 冗余存储） */
    @Field(type = FieldType.Keyword)
    private String name;

    /** 笔记可见性：0=公开，1=仅自己可见（搜索时按此过滤） */
    @Field(type = FieldType.Integer)
    private Integer visible;
}
