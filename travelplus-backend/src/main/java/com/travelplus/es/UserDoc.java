package com.travelplus.es;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.InnerField;
import org.springframework.data.elasticsearch.annotations.MultiField;

/**
 * 【Elasticsearch搜索的实现】用户搜索文档（ES 索引 user_es，从 tb_user 同步）。
 * <p>
 * 实现细节（分词策略取舍）：
 * - nickName 用 text + standard 标准分词（中文按字切分，match_phrase 实现 LIKE 等价连续子串命中，不装 IK），
 *   另加 keyword 子字段 nickName.keyword 支持精确匹配。
 * - 仅索引 id/nickName/icon 三个展示字段，不携带 phone/password 等敏感信息（与 UserDTO 脱敏口径一致）。
 * </p>
 *
 * @author TravelPlus
 */
@Data
@Document(indexName = "user_es")
public class UserDoc {

    /** 用户主键（雪花 Long） */
    @Id
    private Long id;

    /** 用户昵称（text 标准分词 + keyword 子字段 nickName.keyword） */
    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "standard"),
            otherFields = @InnerField(suffix = "keyword", type = FieldType.Keyword))
    private String nickName;

    /** 用户头像路径 */
    @Field(type = FieldType.Keyword)
    private String icon;
}
