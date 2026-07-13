package com.rainexis.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("t_semester")
public class TSemester {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;

    /** active / archived */
    @TableField("status")
    private String status;

    @TableField("created_by")
    private Long createdBy;

    @TableField("archived_at")
    private LocalDateTime archivedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
