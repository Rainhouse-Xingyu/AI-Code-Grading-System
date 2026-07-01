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
 * 教师评审表实体类
 * 教师在AI评分的基础上进行人工复核，可修改评分和评语，形成最终成绩
 */
@Getter
@Setter
@TableName("t_teacher_review")
@ApiModel(value = "TTeacherReview对象", description = "教师评审实体")
public class TTeacherReview implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 评审主键ID，自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联提交记录ID */
    @TableField("submission_id")
    private Long submissionId;

    /** 关联AI评分报告ID */
    @TableField("ai_report_id")
    private Long aiReportId;

    /** 评审教师ID */
    @TableField("teacher_id")
    private Long teacherId;

    /** 教师确认/修改后的最终分数 */
    @TableField("final_score")
    private BigDecimal finalScore;

    /** 教师的最终评语 */
    @TableField("final_comment")
    private String finalComment;

    /** 教师修改后的评分维度结构JSON */
    @ApiModelProperty("修改后的评分结构")
    @TableField("modified_json")
    private String modifiedJson;

    /** 教师修改后的Markdown格式报告全文 */
    @ApiModelProperty("教师修改后的Markdown报告全文")
    @TableField("modified_markdown")
    private String modifiedMarkdown;

    /** 评审记录创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
