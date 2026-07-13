package com.rainexis.backend.controller.api;

import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.entity.TSemester;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.service.business.SemesterService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/semesters")
public class SemesterApiController {
    private final SemesterService semesterService;

    public SemesterApiController(SemesterService semesterService) {
        this.semesterService = semesterService;
    }

    @GetMapping
    public ApiResponse<List<TSemester>> list() {
        AuthContext.requireTeacher();
        return ApiResponse.ok(semesterService.list());
    }

    @PostMapping
    public ApiResponse<TSemester> create(@RequestBody SemesterRequest request) {
        AuthContext.requireTeacher();
        return ApiResponse.ok(semesterService.create(request == null ? null : request.name(), AuthContext.get().id()));
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<TSemester> archive(@PathVariable Long id) {
        AuthContext.requireTeacher();
        return ApiResponse.ok(semesterService.archive(id));
    }

    public record SemesterRequest(String name) {
    }
}
