ALTER TABLE `t_user`
    ADD COLUMN `token_version` INT DEFAULT 0 COMMENT 'Token 版本号，修改密码后递增' AFTER `locked_until`;
