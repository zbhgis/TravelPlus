package com.travelplus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 【景点实体的实现】景点表 tb_attraction 实体，一个景点归属一个类型（typeId），
 * 包含名称、商圈/地址、经纬度（x,y，配合 GEO 按距离检索）、均价（整数）、销量、评论数与评分（×10 存储避免小数）。
 * images 为逗号分隔的多图路径，/imgs/ 开头；distance 为 @TableField(exist=false) 的非表字段，用于列表按距离展示。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_attraction")
public class Attraction implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 景点名称
     */
    private String name;

    /**
     * 景点类型的id
     */
    private Long typeId;

    /**
     * 景点图片，多个图片以','隔开
     */
    private String images;

    /**
     * 商圈，例如陆家嘴
     */
    private String area;

    /**
     * 地址
     */
    private String address;

    /**
     * 经度
     */
    private Double x;

    /**
     * 维度
     */
    private Double y;

    /**
     * 均价，取整数
     */
    private Long avgPrice;

    /**
     * 销量
     */
    private Integer sold;

    /**
     * 评论数量
     */
    private Integer comments;

    /**
     * 评分，1~5分，乘10保存，避免小数
     */
    private Integer score;

    /**
     * 营业时间，例如 10:00-22:00
     */
    private String openHours;

    /**
     * 【城市推荐景点的实现】目的地城市（如 杭州/厦门），城市推荐按本列筛选；种子默认 '杭州'
     * （千岛湖/乌镇归杭州旅游圈），厦门景点为 '厦门'
     */
    private String destination;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


    @TableField(exist = false)
    private Double distance;

    /**
     * 【动态评分的实现】动态均分（×10 存储，与 score 同量纲，前端 /10 展示）。
     * 非表字段：查询详情时实时计算 AVG(tb_attraction_rating.score) * 10，
     * 无评分时回退景点原 score（tb_attraction.score），见 AttractionServiceImpl.queryById 注释。
     */
    @TableField(exist = false)
    private Double avgScore;

    /**
     * 【动态评分的实现】参与打分的用户数（实时 COUNT(tb_attraction_rating)，非表字段）
     */
    @TableField(exist = false)
    private Integer ratingCount;

    /**
     * 【动态评分的实现】当前登录用户自己的打分 1~5（非表字段；未登录或未打分为 null）
     */
    @TableField(exist = false)
    private Integer myRating;

    /**
     * 【打卡与足迹的实现】当前登录用户是否已打卡该景点（非表字段；未登录保持 null）
     */
    @TableField(exist = false)
    private Boolean checkedIn;

    /**
     * 【打卡与足迹的实现】该景点打卡总人数（实时 COUNT(tb_attraction_checkin)，非表字段，"来过"统计）
     */
    @TableField(exist = false)
    private Integer checkinCount;

    /**
     * 【景点点赞的实现】当前登录用户是否已点赞该景点（登录用户查询详情时补充，未登录保持 null）
     * <p>
     * 实现细节：与 TravelNote.isLike 同模式——@TableField(exist=false) 非表字段，不映射
     * tb_attraction 表列（景点无 liked 点赞计数字段），由 queryById 查询时经 Redis ZSet
     * attraction:liked:{id} 判断（含当前用户 id 即已赞），详情响应透出供前端点赞按钮回显。
     */
    @TableField(exist = false)
    private Boolean isLike;

    /**
     * 是否收藏过了（登录用户查询时补充，未登录为 null）
     */
    @TableField(exist = false)
    private Boolean isFavorite;

    /**
     * 收藏数量（详情查询时实时统计）
     */
    @TableField(exist = false)
    private Integer favoriteCount;

    /**
     * 【门票信息的实现】该景点是否有门票（非表字段；列表/详情批量判定）：
     * 有 tb_ticket 记录 = 收费（前端展示门票价），无记录 = 免费（前端展示"免费"）。
     */
    @TableField(exist = false)
    private Boolean hasTicket;

    /**
     * 【门票信息的实现】该景点是否有秒杀票（非表字段；列表/详情批量判定）：
     * tb_seckill_ticket join tb_ticket 命中该景点 = 有秒杀活动（前端展示秒杀入口）。
     */
    @TableField(exist = false)
    private Boolean hasSeckill;
}
