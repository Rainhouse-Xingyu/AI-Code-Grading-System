package com.rainexis.backend.config;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DatabaseMigrationConfigTests {

    @Test
    void migrateDoesNotFailWhenOnlyArchivedSemestersExist() {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:semester_migration_only_archived;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa",
                "");
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);

        jdbcTemplate.update("""
                INSERT INTO t_semester (id, name, status, archived_at)
                VALUES (101, '已归档学期', 'archived', CURRENT_TIMESTAMP)
                """);
        jdbcTemplate.update("""
                INSERT INTO t_user (id, username, password, role)
                VALUES (201, 'migration_student', 'unused', 'student')
                """);
        jdbcTemplate.update("""
                INSERT INTO t_assignment (id, title, teacher_id, semester_id)
                VALUES (301, '待迁移作业', 999, NULL)
                """);

        DatabaseMigrationConfig migration = new DatabaseMigrationConfig(jdbcTemplate);
        assertThatCode(() -> {
            migration.migrate();
            migration.migrate();
        })
                .doesNotThrowAnyException();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_semester WHERE id = 101 AND status = 'archived'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_semester WHERE status = 'active'",
                Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_assignment WHERE id = 301 AND semester_id IS NULL",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_semester_student",
                Integer.class)).isZero();
    }
}
