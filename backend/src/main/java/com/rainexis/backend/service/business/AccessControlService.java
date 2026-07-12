package com.rainexis.backend.service.business;

import com.rainexis.backend.common.BusinessException;
import com.rainexis.backend.entity.TAssignment;
import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.entity.TUser;
import com.rainexis.backend.mapper.TAssignmentMapper;
import com.rainexis.backend.mapper.TSubmissionMapper;
import com.rainexis.backend.mapper.TUserMapper;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.security.AuthUser;
import org.springframework.stereotype.Service;

/**
 * 访问权限控制服务
 * 封装跨实体间的权限校验逻辑，确保用户只能操作自己有权访问的资源
 * - 教师只能管理自己创建的作业、本班学生
 * - 学生只能访问自己的提交、本班已发布的作业
 */
@Service
public class AccessControlService {
    private final TAssignmentMapper assignmentMapper;
    private final TSubmissionMapper submissionMapper;
    private final TUserMapper userMapper;
    private final AssignmentClassService assignmentClassService;

    public AccessControlService(TAssignmentMapper assignmentMapper,
                                TSubmissionMapper submissionMapper,
                                TUserMapper userMapper,
                                AssignmentClassService assignmentClassService) {
        this.assignmentMapper = assignmentMapper;
        this.submissionMapper = submissionMapper;
        this.userMapper = userMapper;
        this.assignmentClassService = assignmentClassService;
    }

    /** 校验当前用户是管理员且是作业发布管理者。普通教师不能创建、编辑、发布作业。 */
    public TAssignment requireAssignmentOwner(Long assignmentId) {
        AuthContext.requireAdmin();
        TAssignment assignment = assignmentMapper.selectById(assignmentId);
        if (assignment == null) {
            throw BusinessException.notFound("作业不存在");
        }
        assignmentClassService.attachClassNames(assignment);
        return assignment;
    }

    /** 校验教师可访问作业：管理员可访问全部，普通教师只能访问发布到本班的作业。 */
    public TAssignment requireAssignmentAccess(Long assignmentId) {
        AuthContext.requireTeacher();
        TAssignment assignment = assignmentMapper.selectById(assignmentId);
        if (assignment == null) {
            throw BusinessException.notFound("作业不存在");
        }
        AuthUser current = AuthContext.get();
        if (!current.isAdmin() && !assignmentClassService.includesClass(assignment, current.className())) {
            throw BusinessException.forbidden("只能访问本班作业");
        }
        assignmentClassService.attachClassNames(assignment);
        return assignment;
    }

    /** 校验学生是否有权查看该作业（已发布且同班） */
    public TAssignment requireStudentCanViewAssignment(Long assignmentId) {
        AuthContext.requireStudent();
        TAssignment assignment = assignmentMapper.selectById(assignmentId);
        if (assignment == null || !"published".equals(assignment.getStatus())) {
            throw BusinessException.notFound("作业不存在或未发布");
        }
        if (!assignmentClassService.includesClass(assignment, AuthContext.get().className())) {
            throw BusinessException.forbidden("只能查看本班作业");
        }
        assignmentClassService.attachClassNames(assignment);
        return assignment;
    }

    /** 校验教师有权访问指定提交记录 */
    public TSubmission requireTeacherSubmissionAccess(Long submissionId) {
        AuthContext.requireTeacher();
        TSubmission submission = submissionMapper.selectById(submissionId);
        if (submission == null) {
            throw BusinessException.notFound("提交不存在");
        }
        requireAssignmentAccess(submission.getAssignmentId());
        if (!canTeacherAccessSubmission(submission)) {
            throw BusinessException.forbidden("只能访问本班学生提交");
        }
        return submission;
    }

    public boolean canTeacherAccessSubmission(Long submissionId) {
        TSubmission submission = submissionMapper.selectById(submissionId);
        return canTeacherAccessSubmission(submission);
    }

    public boolean canTeacherAccessSubmission(TSubmission submission) {
        if (submission == null) {
            return false;
        }
        AuthUser current = AuthContext.get();
        if (current.isAdmin()) {
            return true;
        }
        TAssignment assignment = assignmentMapper.selectById(submission.getAssignmentId());
        if (!assignmentClassService.includesClass(assignment, current.className())) {
            return false;
        }
        TUser student = userMapper.selectById(submission.getStudentId());
        return student != null && sameClass(current.className(), student.getClassName());
    }

    /** 校验学生只能访问自己的提交记录 */
    public TSubmission requireStudentOwnSubmission(Long submissionId) {
        AuthContext.requireStudent();
        TSubmission submission = submissionMapper.selectById(submissionId);
        if (submission == null || !AuthContext.get().id().equals(submission.getStudentId())) {
            throw BusinessException.forbidden("只能访问自己的提交");
        }
        return submission;
    }

    /** 校验教师有权管理指定学生（必须是教师本班的学生） */
    public TUser requireTeacherCanManageStudent(Long studentId) {
        AuthContext.requireTeacher();
        TUser student = userMapper.selectById(studentId);
        if (student == null || !"student".equals(student.getRole())) {
            throw BusinessException.notFound("学生不存在");
        }
        AuthUser current = AuthContext.get();
        if (!current.isAdmin() && !sameClass(current.className(), student.getClassName())) {
            throw BusinessException.forbidden("只能管理自己班级的学生");
        }
        return student;
    }

    public boolean canReadAssignmentStats(AuthUser current, TAssignment assignment) {
        return assignment != null
                && (current.isAdmin() || assignmentClassService.includesClass(assignment, current.className()));
    }

    public boolean sameClass(String expected, String actual) {
        return expected != null && !expected.isBlank() && expected.equals(actual);
    }
}
