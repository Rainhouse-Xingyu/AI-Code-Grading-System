package com.rainexis.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 提交记录表实体类
 * 学生提交的编程作业记录，支持多版本提交，每个学生每次作业可以有多个提交版本
 */
@Getter
@Setter
@TableName("t_submission")
@ApiModel(value = "TSubmission对象", description = "提交记录实体")
public class TSubmission implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 提交记录主键ID，自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联作业ID */
    @TableField("assignment_id")
    private Long assignmentId;

    /** 提交学生ID，关联t_user.id */
    @TableField("student_id")
    private Long studentId;

    /** 提交文件的存储URL */
    @TableField("file_url")
    private String fileUrl;

    /** 原始文件名 */
    @TableField("file_name")
    private String fileName;

    /** 提交版本号（同一学生同一作业，每次上传递增） */
    @ApiModelProperty("同一学生同一作业的提交版本号")
    @TableField("submission_version")
    private Integer submissionVersion;

    /** 是否为当前有效提交（最新版本） */
    @ApiModelProperty("是否为当前有效提交")
    @TableField("is_current")
    private Boolean current;

    /** 文件上传时间 */
    @TableField("upload_time")
    private LocalDateTime uploadTime;

    /** 是否迟交（在截止时间之后提交） */
    @ApiModelProperty("是否迟交")
    @TableField("is_late")
    private Boolean late;

    /** 处理状态：uploaded（已上传）/ scoring（评分中）/ done（评分完成）/ failed（评分失败） */
    @ApiModelProperty("uploaded/scoring/done/failed")
    @TableField("status")
    private String status;

    /** 当前评分总分 */
    @TableField("current_score")
    private BigDecimal currentScore;

    /** 当前关联的AI评分报告ID */
    @TableField("current_report_id")
    private Long currentReportId;

    /** 关联代码结构ID，指向t_project_structure.id */
    @ApiModelProperty("关联t_project_structure.id")
    @TableField("project_structure_id")
    private Long projectStructureId;

    /** 编程语言类型（java / python / c / cpp 等） */
    @ApiModelProperty("编程语言类型(java/python/c/cpp等)")
    @TableField("language")
    private String language;

    /** 提交中包含的代码文件数量 */
    @ApiModelProperty("代码文件数量")
    @TableField("file_count")
    private Integer fileCount;
}
