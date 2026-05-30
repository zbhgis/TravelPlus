package com.travelplus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>
 * 【用户详情实体的实现】用户扩展信息表 tb_user_info 实体，主键 userId 与 tb_user.id 一一对应，
 * 保存城市/介绍/粉丝数/关注数/性别/生日/积分/会员级别等主页展示信息，
 * 以及隐私可见性开关（1 可见 / 0 不可见）：followVisible/fansVisible 关注粉丝列表、
 * likesVisible/favoritesVisible/commentsVisible/footprintsVisible 赞过/收藏/评论/足迹列表，
 * 以及私信模式 dmMode（0 开放 / 1 仅互关 / 2 关闭）。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_user_info")
public class UserInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键，用户id
     */
    @TableId(value = "user_id", type = IdType.AUTO)
    private Long userId;

    /**
     * 城市名称
     */
    private String city;

    /**
     * 个人介绍，不要超过128个字符
     */
    private String introduce;

    /**
     * 粉丝数量
     */
    private Integer fans;

    /**
     * 关注的人的数量
     */
    private Integer followee;

    /**
     * 性别，0：男，1：女
     */
    private Boolean gender;

    /**
     * 生日
     */
    private LocalDate birthday;

    /**
     * 积分
     */
    private Integer credits;

    /**
     * 会员级别，0~9级,0代表未开通会员
     */
    private Boolean level;

    /**
     * 【隐私设置的实现】关注列表是否公开：1=可见（默认），0=不可见（他人无法查看我的关注列表）
     */
    private Integer followVisible;

    /**
     * 【隐私设置的实现】粉丝列表是否公开：1=可见（默认），0=不可见（他人无法查看我的粉丝列表）
     */
    private Integer fansVisible;

    /**
     * 【隐私设置的实现】"赞过"列表是否公开：1=可见（默认），0=不可见（他人无法查看我的赞过列表）
     */
    private Integer likesVisible;

    /**
     * 【隐私设置的实现】"收藏"列表是否公开：1=可见（默认），0=不可见（他人无法查看我的收藏列表）
     */
    private Integer favoritesVisible;

    /**
     * 【隐私设置的实现】"评论"列表是否公开：1=可见（默认），0=不可见（他人无法查看我的评论列表）
     */
    private Integer commentsVisible;

    /**
     * 【隐私设置的实现】"足迹/打卡"列表是否公开：1=可见（默认），0=不可见（他人无法查看我的足迹列表）
     */
    private Integer footprintsVisible;

    /**
     * 【私信限制的实现】私信模式：0=开放（所有人可发，陌生人回复前每日 1 条），1=仅互关好友可发，
     * 2=关闭（任何人不可发）。仅影响"我"作为接收方的私信策略，见 ChatServiceImpl.send 校验注释。
     */
    private Integer dmMode;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
