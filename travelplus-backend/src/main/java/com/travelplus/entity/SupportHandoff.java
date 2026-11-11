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
 * 【企业级客服Agent的实现】人工转接工单实体 tb_support_handoff。
 * <p>
 * 状态机：0=待接入（AI 已静默）→ 1=人工服务中 → 2=已结束（恢复 AI）。
 * source 记录来源：user=用户主动转人工，agent=Agent 工具/投诉策略自动转接。
 *
 * @author TravelPlus
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_support_handoff")
public class SupportHandoff implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 咨询用户 id */
    private Long userId;

    /** 转人工原因 */
    private String reason;

    /** 0=待接入 1=人工服务中 2=已结束 */
    private Integer status;

    /** 来源：user/agent */
    private String source;

    /** 接入的人工客服 id */
    private Long assigneeId;

    private LocalDateTime createTime;

    private LocalDateTime claimTime;

    private LocalDateTime closeTime;
}
