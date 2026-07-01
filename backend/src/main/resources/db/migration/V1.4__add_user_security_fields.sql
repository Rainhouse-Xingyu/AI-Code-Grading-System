ALTER TABLE `t_user`
    ADD COLUMN `need_password_change` TINYINT DEFAULT 0 COMMENT '是否需要修改初始密码' AFTER `class_name`,
    ADD COLUMN `login_fail_count` INT DEFAULT 0 COMMENT '连续登录失败次数' AFTER `need_password_change`,
    ADD COLUMN `locked_until` DATETIME DEFAULT NULL COMMENT '锁定截止时间' AFTER `login_fail_count`;
