package com.travelplus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 【用户实体的实现】用户表 tb_user 实体，认证与用户基础信息。
 * phone 为登录账号，password 经 PasswordEncoder 加盐 MD5 存储（{盐}@{摘要}）；
 * 昵称默认 user_{id} 格式；icon 默认指向系统预置头像（见字段注释），编辑主页可上传覆盖。
 * </p>
 *
 * @author TravelPlus
 * @since 2021-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_user")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 手机号码
     */
    private String phone;

    /**
     * 密码，加密存储
     */
    private String password;

    /**
     * 昵称，默认是随机字符
     */
    private String nickName;

    /**
     * 用户头像
     * 【注册默认头像的实现】默认指向系统预置的默认头像 /imgs/user/avatar/default.png（DiceBear 生成），
     * 新注册用户在未上传头像前即可展示统一默认头像；用户编辑主页上传后覆盖该字段。
     */
    private String icon = "/imgs/user/avatar/default.png";

    /**
     * 【用户主页编辑的实现】用户背景图路径（个人主页编辑上传，默认空串=无背景图）
     */
    private String bgImage = "";

    /**
     * 【签到积分与钱包的实现】积分余额（每天签到 +2，SQL 原子自增，见 UserServiceImpl.sign 注释）
     */
    private Integer points = 0;

    /**
     * 【签到积分与钱包的实现】钱包余额（元），demo 预置/运营调整，配合秒杀券支付预留
     */
    private BigDecimal balance = BigDecimal.ZERO;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;


}
