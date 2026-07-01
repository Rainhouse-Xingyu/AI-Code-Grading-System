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
 * 文件存储表实体类
 * 记录所有上传文件的元数据，包括原始文件名、存储名、文件类型和大小等
 *
 * @author xingyu
 * @since 2026-06-25
 */
@Getter
@Setter
@TableName("t_file")
@ApiModel(value = "TFile对象", description = "文件存储实体")
public class TFile implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 文件主键ID，自增 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 文件原始名称 */
    @TableField("file_name")
    private String fileName;

    /** 重命名后的存储文件名（防止同名冲突） */
    @ApiModelProperty("重命名后的存储文件名")
    @TableField("storage_name")
    private String storageName;

    /** 文件存储URL/路径 */
    @TableField("file_url")
    private String fileUrl;

    /** 文件类型：zip / java / docx / xlsx */
    @ApiModelProperty("zip/java/docx/xlsx")
    @TableField("file_type")
    private String fileType;

    /** 文件大小（字节） */
    @TableField("file_size")
    private Long fileSize;

    /** 上传者用户ID */
    @TableField("uploader_id")
    private Long uploaderId;

    /** 记录创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
