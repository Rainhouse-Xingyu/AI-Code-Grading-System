package com.rainexis.backend.service.business;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TSemester;
import com.rainexis.backend.mapper.TSemesterMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SemesterService {
    private final TSemesterMapper semesterMapper;
    private final JdbcTemplate jdbcTemplate;

    public SemesterService(TSemesterMapper semesterMapper, JdbcTemplate jdbcTemplate) {
        this.semesterMapper = semesterMapper;
        this.jdbcTemplate = jdbcTemplate;
    }

    public TSemester current() {
        TSemester semester = semesterMapper.selectOne(new LambdaQueryWrapper<TSemester>()
                .eq(TSemester::getStatus, "active")
                .orderByDesc(TSemester::getCreatedAt)
                .last("limit 1"));
        if (semester == null) {
            throw BusinessException.conflict("当前没有进行中的学期，请先新建学期");
        }
        return semester;
    }

    public List<TSemester> list() {
        return semesterMapper.selectList(new LambdaQueryWrapper<TSemester>()
                .orderByDesc(TSemester::getCreatedAt));
    }

    public TSemester create(String name, Long createdBy) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.isEmpty()) {
            throw BusinessException.badRequest("学期名称不能为空");
        }
        if (semesterMapper.selectCount(new LambdaQueryWrapper<TSemester>().eq(TSemester::getStatus, "active")) > 0) {
            throw BusinessException.conflict("请先归档当前学期，再新建学期");
        }
        TSemester semester = new TSemester();
        semester.setName(cleaned);
        semester.setStatus("active");
        semester.setCreatedBy(createdBy);
        semester.setCreatedAt(LocalDateTime.now());
        semesterMapper.insert(semester);
        return semester;
    }

    public TSemester archive(Long id) {
        TSemester semester = semesterMapper.selectById(id);
        if (semester == null) {
            throw BusinessException.notFound("学期不存在");
        }
        if (!"active".equals(semester.getStatus())) {
            throw BusinessException.conflict("该学期已归档");
        }
        semester.setStatus("archived");
        semester.setArchivedAt(LocalDateTime.now());
        semesterMapper.updateById(semester);
        return semester;
    }

    public void requireActive(Long semesterId) {
        TSemester semester = semesterMapper.selectById(semesterId);
        if (semester == null || !"active".equals(semester.getStatus())) {
            throw BusinessException.conflict("该学期已归档，仅可查看历史数据");
        }
    }

    public void enrollStudent(Long semesterId, Long studentId) {
        jdbcTemplate.update("INSERT INTO t_semester_student (semester_id, student_id) SELECT ?, ? WHERE NOT EXISTS (SELECT 1 FROM t_semester_student WHERE semester_id = ? AND student_id = ?)", semesterId, studentId, semesterId, studentId);
    }

    public List<Long> studentIds(Long semesterId) {
        return jdbcTemplate.queryForList("SELECT student_id FROM t_semester_student WHERE semester_id = ?", Long.class, semesterId);
    }

    public boolean isStudentEnrolled(Long semesterId, Long studentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_semester_student WHERE semester_id = ? AND student_id = ?",
                Integer.class, semesterId, studentId);
        return count != null && count > 0;
    }

    public void removeStudent(Long studentId) {
        jdbcTemplate.update("DELETE FROM t_semester_student WHERE student_id = ?", studentId);
    }
}
