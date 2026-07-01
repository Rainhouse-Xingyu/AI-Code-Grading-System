package com.rainexis.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户表实体类
 * 存储系统用户（教师和学生）的基本信息，包括登录凭证、角色、锁定状态等
 *
 * @author xingyu
 * @since 2026-06-25
 */
@Getter
@Setter
@TableName("t_user")
@ApiModel(value = "TUser对象", description = "用户实体")
public class TUser implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户主键ID，自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 登录用户名 */
    @TableField("username")
    private String username;

    /** 加密后的密码 */
    @TableField("password")
    private String password;

    /** 角色：student（学生）/ teacher（教师） */
    @ApiModelProperty("student / teacher")
    @TableField("role")
    private String role;

    /** 用户真实姓名 */
    @TableField("real_name")
    private String realName;

    /** 电子邮箱 */
    @TableField("email")
    private String email;

    /** 联系电话 */
    @TableField("phone")
    private String phone;

    /** 所属班级 */
    @TableField("class_name")
    private String className;

    /** 是否需要修改初始密码（首次登录强制修改） */
    @ApiModelProperty("是否需要修改初始密码")
    @TableField("need_password_change")
    private Boolean needPasswordChange;

    /** 连续登录失败次数，用于账号锁定判断 */
    @ApiModelProperty("连续登录失败次数")
    @TableField("login_fail_count")
    private Integer loginFailCount;

    /** 账号锁定截止时间，超过此时间自动解锁 */
    @ApiModelProperty("锁定截止时间")
    @TableField("locked_until")
    private LocalDateTime lockedUntil;

    /** Token版本号，修改密码后递增，使旧Token失效 */
    @ApiModelProperty("Token 版本号，修改密码后递增")
    @TableField("token_version")
    private Integer tokenVersion;

    /** 记录创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /** 记录最后更新时间 */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
