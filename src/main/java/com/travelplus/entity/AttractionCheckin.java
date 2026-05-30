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
 * <p>
 * 【打卡与足迹实体的实现】景点打卡表 tb_attraction_checkin 实体，一个用户对某景点打卡一次，
 * 唯一键 uk_ac(user_id, attraction_id) 保证"一人一景点只打卡一次"——重复打卡幂等（已打卡直接返回 ok，
 * 不重复计数，见 AttractionServiceImpl.checkin 注释）。
 * 打卡记录即用户足迹来源：某用户全部打卡记录按时间倒序 = 该用户足迹列表（/user/checkins/{id}）；
 * 某景点打卡记录数 = 该景点"来过"统计（checkinCount）。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_attraction_checkin")
public class AttractionCheckin implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 景点id
     */
    private Long attractionId;

    /**
     * 打卡用户id
     */
    private Long userId;

    /**
     * 打卡时间（=足迹时间）
     */
    private LocalDateTime createTime;
}
