package com.rainexis.backend.service.business;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.entity.TAssignment;
import com.rainexis.backend.entity.TAssignmentClass;
import com.rainexis.backend.mapper.TAssignmentClassMapper;
import com.rainexis.backend.security.AuthUser;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class AssignmentClassService {
    private final TAssignmentClassMapper assignmentClassMapper;

    public AssignmentClassService(TAssignmentClassMapper assignmentClassMapper) {
        this.assignmentClassMapper = assignmentClassMapper;
    }

    public List<String> normalize(List<String> classNames) {
        if (classNames == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String className : classNames) {
            if (className == null || className.isBlank()) {
                continue;
            }
            for (String part : className.split("[,，]")) {
                if (!part.isBlank()) {
                    unique.add(part.trim());
                }
            }
        }
        return List.copyOf(unique);
    }

    public void replaceClasses(Long assignmentId, List<String> classNames) {
        assignmentClassMapper.delete(new LambdaQueryWrapper<TAssignmentClass>()
                .eq(TAssignmentClass::getAssignmentId, assignmentId));
        for (String className : normalize(classNames)) {
            TAssignmentClass row = new TAssignmentClass();
            row.setAssignmentId(assignmentId);
            row.setClassName(className);
            row.setCreatedAt(LocalDateTime.now());
            assignmentClassMapper.insert(row);
        }
    }

    public List<String> classNames(TAssignment assignment) {
        if (assignment == null || assignment.getId() == null) {
            return List.of();
        }
        List<String> linkedClasses = assignmentClassMapper.selectList(new LambdaQueryWrapper<TAssignmentClass>()
                        .eq(TAssignmentClass::getAssignmentId, assignment.getId())
                        .orderByAsc(TAssignmentClass::getId))
                .stream()
                .map(TAssignmentClass::getClassName)
                .filter(value -> value != null && !value.isBlank())
                .toList();
        if (!linkedClasses.isEmpty()) {
            return normalize(linkedClasses);
        }
        return parseLegacyClassName(assignment.getClassName());
    }

    public TAssignment attachClassNames(TAssignment assignment) {
        if (assignment != null) {
            assignment.setClassNames(classNames(assignment));
        }
        return assignment;
    }

    public List<TAssignment> attachClassNames(List<TAssignment> assignments) {
        assignments.forEach(this::attachClassNames);
        return assignments;
    }

    public List<Long> assignmentIdsForClass(String className) {
        if (className == null || className.isBlank()) {
            return List.of();
        }
        return assignmentClassMapper.selectList(new LambdaQueryWrapper<TAssignmentClass>()
                        .eq(TAssignmentClass::getClassName, className.trim()))
                .stream()
                .map(TAssignmentClass::getAssignmentId)
                .distinct()
                .toList();
    }

    public boolean includesClass(TAssignment assignment, String className) {
        if (assignment == null) {
            return false;
        }
        List<String> classes = classNames(assignment);
        if (classes.isEmpty()) {
            return className == null || className.isBlank();
        }
        return className != null && classes.contains(className);
    }

    public List<String> visibleClassNames(TAssignment assignment, AuthUser current) {
        List<String> classes = classNames(assignment);
        if ("admin".equals(current.role())) {
            return classes;
        }
        if (current.className() == null || current.className().isBlank()) {
            return List.of();
        }
        if (classes.isEmpty() || classes.contains(current.className())) {
            return List.of(current.className());
        }
        return List.of();
    }

    public List<String> parseLegacyClassName(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) {
                result.add(part.trim());
            }
        }
        return normalize(result);
    }
}
