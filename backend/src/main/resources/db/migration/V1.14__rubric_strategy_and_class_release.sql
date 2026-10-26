ALTER TABLE t_rubric_template_item
    ADD COLUMN scoring_method VARCHAR(30) DEFAULT NULL,
    ADD COLUMN score_bands_json LONGTEXT;
ALTER TABLE t_assignment
    ADD COLUMN parent_assignment_id BIGINT DEFAULT NULL,
    ADD COLUMN teacher_release_required TINYINT DEFAULT 0,
    ADD CONSTRAINT uq_assignment_parent_class UNIQUE (parent_assignment_id, class_name);
