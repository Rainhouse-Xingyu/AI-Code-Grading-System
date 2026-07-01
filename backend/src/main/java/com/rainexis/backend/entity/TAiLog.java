package com.rainexis.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * AI评分日志表实体类
 * 记录AI评分过程中产生的日志信息，包括日志级别、消息内容和调用耗时
 */
@Getter
@Setter
@TableName("t_ai_log")
public class TAiLog implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 日志主键ID，自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联AI评分任务ID */
    @TableField("task_id")
    private Long taskId;

    /** 关联提交记录ID */
    @TableField("submission_id")
    private Long submissionId;

    /** 日志级别：info / warn / error */
    @TableField("level")
    private String level;

    /** 日志消息内容 */
    @TableField("message")
    private String message;

    /** 使用的AI模型名称 */
    @TableField("model_name")
    private String modelName;

    /** 操作耗时（毫秒） */
    @TableField("duration_ms")
    private Long durationMs;

    /** 日志记录创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
