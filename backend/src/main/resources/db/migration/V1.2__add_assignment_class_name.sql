ALTER TABLE `t_assignment`
    ADD COLUMN `class_name` VARCHAR(50) DEFAULT NULL COMMENT '作业所属班级' AFTER `language`;
