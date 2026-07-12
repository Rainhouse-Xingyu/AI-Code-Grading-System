package com.rainexis.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * 作业表实体类
 * 教师发布的编程作业，包含题目描述、时间期限、迟交策略等配置
 */
@Getter
@Setter
@TableName("t_assignment")
@ApiModel(value = "TAssignment对象", description = "作业实体")
public class TAssignment implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 作业主键ID，自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 作业标题 */
    @TableField("title")
    private String title;

    /** 课程名称，用于评分报告标题 */
    @TableField("course_name")
    private String courseName;

    /** 作业描述/要求 */
    @TableField("description")
    private String description;

    /** 发布作业的教师ID，关联t_user.id */
    @TableField("teacher_id")
    private Long teacherId;

    /** 编程语言类型：java / python / c / cpp */
    @ApiModelProperty("编程语言 java/python/c/cpp")
    @TableField("language")
    private String language;

    /** 作业所属班级 */
    @ApiModelProperty("作业所属班级")
    @TableField("class_name")
    private String className;

    /** 多班级发布时的班级列表，来自 t_assignment_class */
    @TableField(exist = false)
    private List<String> classNames;

    /** 作业开始时间（允许提交起始时间） */
    @TableField("start_time")
    private LocalDateTime startTime;

    /** 作业截止时间 */
    @TableField("end_time")
    private LocalDateTime endTime;

    /** 迟交策略：forbid（禁止迟交）/ allow_mark（允许但标记）/ allow_penalty（允许但扣分） */
    @ApiModelProperty("迟交策略: forbid/allow_mark/allow_penalty")
    @TableField("late_policy")
    private String latePolicy;

    /** 迟交扣分比例（百分比），取值范围 0-100 */
    @ApiModelProperty("迟交扣分比例，0-100")
    @TableField("late_penalty_percent")
    private Integer latePenaltyPercent;

    @TableField("rubric_template_id")
    private Long rubricTemplateId;

    @TableField("selected_rubric_item_ids")
    private String selectedRubricItemIds;

    @TableField("normalized_rubric_json")
    private String normalizedRubricJson;

    /** 作业状态：draft（草稿）/ published（已发布）/ closed（已关闭） */
    @ApiModelProperty("draft/published/closed")
    @TableField("status")
    private String status;

    /** 记录创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
