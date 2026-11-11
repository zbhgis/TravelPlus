package com.travelplus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 【企业级客服Agent的实现】客服回复反馈实体 tb_support_feedback。
 * <p>
 * 用户对单条客服回复点赞/点踩，用于在线质量评估与 bad case 回收；
 * messageId 关联 tb_chat_message.id，intent 便于按意图统计满意度。
 *
 * @author TravelPlus
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_support_feedback")
public class SupportFeedback implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long messageId;

    /** 客服意图 */
    private String intent;

    /** 1=有帮助 0=没帮助 */
    private Boolean helpful;

    /** 点踩原因（可选） */
    private String reason;

    private LocalDateTime createTime;
}
