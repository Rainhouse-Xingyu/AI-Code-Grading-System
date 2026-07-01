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
 * AI评分报告表实体类
 * 存储AI对提交作业的完整评分报告，包括总分、各维度得分、逐个文件分析及改进建议
 */
@Getter
@Setter
@TableName("t_ai_report")
@ApiModel(value = "TAiReport对象", description = "AI评分报告实体")
public class TAiReport implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 报告主键ID，自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联提交记录ID */
    @TableField("submission_id")
    private Long submissionId;

    /** 关联AI评分任务ID */
    @TableField("task_id")
    private Long taskId;

    /** 使用的AI模型名称（deepseek / qwen / local 等） */
    @ApiModelProperty("使用的模型名称(deepseek/qwen/local)")
    @TableField("model_name")
    private String modelName;

    /** 评分总分 */
    @TableField("total_score")
    private BigDecimal totalScore;

    /** 各维度得分JSON（已废弃，V1.1后使用score_detail_json替代） */
    @ApiModelProperty("分项评分")
    @TableField("score_json")
    private String scoreJson;

    /** 分项评分详细结构（维度 → 得分 → 评语） */
    @ApiModelProperty("分项评分JSON结构(维度→得分→评语)")
    @TableField("score_detail_json")
    private String scoreDetailJson;

    /** 每个代码文件的分析结果JSON */
    @ApiModelProperty("每个文件的分析结果JSON")
    @TableField("file_analysis_json")
    private String fileAnalysisJson;

    /** 本次评分消耗的Token总数 */
    @ApiModelProperty("本次评分消耗的token总数")
    @TableField("token_usage")
    private Integer tokenUsage;

    /** AI生成的完整评分报告（Markdown格式） */
    @ApiModelProperty("AI生成报告")
    @TableField("report_markdown")
    private String reportMarkdown;

    /** AI给出的代码改进建议 */
    @TableField("suggestion")
    private String suggestion;

    /** 记录创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
