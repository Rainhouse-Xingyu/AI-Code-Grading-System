ALTER TABLE `t_user`
    ADD COLUMN `id_card_encrypted` VARCHAR(512) DEFAULT NULL COMMENT '加密后的身份证号' AFTER `class_name`,
    ADD COLUMN `employee_no` VARCHAR(50) DEFAULT NULL COMMENT '教职工号' AFTER `id_card_encrypted`,
    ADD COLUMN `college` VARCHAR(100) DEFAULT NULL COMMENT '所属学院' AFTER `employee_no`,
    ADD COLUMN `teaching_course` VARCHAR(200) DEFAULT NULL COMMENT '教授课程' AFTER `college`,
    ADD COLUMN `teaching_class` VARCHAR(500) DEFAULT NULL COMMENT '教授班级' AFTER `teaching_course`;

ALTER TABLE `t_assignment`
    ADD COLUMN `course_name` VARCHAR(100) DEFAULT NULL COMMENT '课程名称' AFTER `title`;

UPDATE `t_user`
SET `role` = 'admin'
WHERE `role` = 'super_admin'
  AND `username` <> 'superadmin';

DELETE FROM `t_user`
WHERE `username` = 'superadmin';
