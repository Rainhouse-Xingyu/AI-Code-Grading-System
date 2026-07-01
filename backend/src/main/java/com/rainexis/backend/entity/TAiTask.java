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
 * AI评分任务表实体类
 * 记录每次AI评分任务的执行状态、使用的模型、Token消耗以及重试信息
 *
 * @author xingyu
 * @since 2026-06-25
 */
@Getter
@Setter
@TableName("t_ai_task")
@ApiModel(value = "TAiTask对象", description = "AI评分任务实体")
public class TAiTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 任务主键ID，自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联提交记录ID */
    @TableField("submission_id")
    private Long submissionId;

    /** 关联作业ID */
    @TableField("assignment_id")
    private Long assignmentId;

    /** AI模型名称：deepseek / local */
    @ApiModelProperty("deepseek / local")
    @TableField("model_name")
    private String modelName;

    /** 任务执行状态：pending（等待）/ running（执行中）/ success（成功）/ failed（失败） */
    @ApiModelProperty("pending/running/success/failed")
    @TableField("status")
    private String status;

    /** 输入提示词消耗的Token数 */
    @TableField("prompt_tokens")
    private Integer promptTokens;

    /** AI回复消耗的Token数 */
    @TableField("completion_tokens")
    private Integer completionTokens;

    /** Token总消耗量 */
    @TableField("total_tokens")
    private Integer totalTokens;

    /** 任务失败时的错误信息 */
    @TableField("error_message")
    private String errorMessage;

    /** 失败后的重试次数 */
    @ApiModelProperty("失败重试次数")
    @TableField("retry_count")
    private Integer retryCount;

    /** 任务开始执行时间 */
    @TableField("start_time")
    private LocalDateTime startTime;

    /** 任务结束时间 */
    @TableField("end_time")
    private LocalDateTime endTime;

    /** 记录创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
