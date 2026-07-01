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
 * <p>
 * 代码结构存储表 - SpringBoot解压ZIP后生成的JSON化代码结构
 * </p>
 */
@Getter
@Setter
@TableName("t_project_structure")
@ApiModel(value = "TProjectStructure对象", description = "代码结构存储表")
public class TProjectStructure implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("submission_id")
    private Long submissionId;

    @ApiModelProperty("代码JSON结构(file_tree+contents)")
    @TableField("structure_json")
    private String structureJson;

    @ApiModelProperty("编程语言")
    @TableField("language")
    private String language;

    @TableField("file_count")
    private Integer fileCount;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
