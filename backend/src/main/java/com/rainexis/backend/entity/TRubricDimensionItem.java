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
@TableName("t_rubric_dimension_item")
public class TRubricDimensionItem implements Serializable {
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("rubric_id")
    private Long rubricId;

    @TableField("assignment_id")
    private Long assignmentId;

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

    @TableField("created_at")
    private LocalDateTime createdAt;
}
