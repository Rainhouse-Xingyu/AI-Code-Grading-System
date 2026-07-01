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
 * 成绩发布表实体类
 * 管理最终成绩的发布状态，控制学生是否可查看成绩和评语报告
 *
 * @author xingyu
 * @since 2026-06-25
 */
@Getter
@Setter
@TableName("t_grade_publish")
@ApiModel(value = "TGradePublish对象", description = "成绩发布实体")
public class TGradePublish implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 发布记录主键ID，自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联提交记录ID */
    @TableField("submission_id")
    private Long submissionId;

    /** 关联作业ID */
    @TableField("assignment_id")
    private Long assignmentId;

    /** 学生用户ID */
    @TableField("student_id")
    private Long studentId;

    /** 最终成绩分数 */
    @TableField("final_score")
    private BigDecimal finalScore;

    /** 关联的评分报告ID（AI报告或教师复核报告） */
    @TableField("report_id")
    private Long reportId;

    /** 是否已向学生发布（0=未发布, 1=已发布） */
    @TableField("is_published")
    private Byte isPublished;

    /** 成绩发布时间 */
    @TableField("published_at")
    private LocalDateTime publishedAt;
}
