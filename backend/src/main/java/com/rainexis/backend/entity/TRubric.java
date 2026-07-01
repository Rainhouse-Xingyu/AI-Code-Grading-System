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
 * 评分标准表实体类
 * 存储作业的评分标准/评分细则，支持手动录入和从Word/Excel文件自动解析两种来源
 */
@Getter
@Setter
@TableName("t_rubric")
@ApiModel(value = "TRubric对象", description = "评分标准实体")
public class TRubric implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 评分标准主键ID，自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 关联作业ID */
    @TableField("assignment_id")
    private Long assignmentId;

    /** 评分标准来源类型：manual（手动录入）/ auto（Word/Excel解析） */
    @ApiModelProperty("评分标准类型(manual=手动录入/auto=WordExcel解析)")
    @TableField("rubric_type")
    private String rubricType;

    /** 评分标准文件的存储URL */
    @TableField("file_url")
    private String fileUrl;

    /** 结构化的评分标准JSON（维度 → 评分项 → 分值） */
    @ApiModelProperty("结构化评分标准")
    @TableField("rubric_json")
    private String rubricJson;

    /** 评分标准版本号（旧版字段） */
    @TableField("version")
    private Integer version;

    /** 评分标准版本号，每次修改递增 */
    @ApiModelProperty("评分标准版本号，每次修改递增")
    @TableField("rubric_version")
    private Integer rubricVersion;

    /** Word/Excel文件解析后的完整JSON结构，冗余存储便于查询 */
    @ApiModelProperty("Word/Excel解析后的完整JSON结构(冗余存储)")
    @TableField("parsed_json")
    private String parsedJson;

    /** 是否为当前激活的评分标准 */
    @TableField("is_active")
    private Byte isActive;

    /** 记录创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
