package com.rainexis.backend.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("t_rubric_template_item")
public class TRubricTemplateItem implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("template_id")
    private Long templateId;

    @TableField("dimension_order")
    private Integer dimensionOrder;

    @TableField("dimension_name")
    private String dimensionName;

    @TableField("point_order")
    private Integer pointOrder;

    @TableField("point_name")
    private String pointName;

    @TableField("point_score")
    private BigDecimal pointScore;

    @TableField("point_ratio")
    private BigDecimal pointRatio;

    @TableField("criteria")
    private String criteria;

    @TableField("enabled")
    private Byte enabled;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
