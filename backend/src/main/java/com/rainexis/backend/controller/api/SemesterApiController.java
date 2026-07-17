package com.rainexis.backend.controller.api;

import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.entity.TSemester;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.service.business.SemesterFileCleanupService;
import com.rainexis.backend.service.business.SemesterService;
import java.util.List;
import java.util.Map;
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
    private final SemesterFileCleanupService semesterFileCleanupService;

    public SemesterApiController(SemesterService semesterService,
                                 SemesterFileCleanupService semesterFileCleanupService) {
        this.semesterService = semesterService;
        this.semesterFileCleanupService = semesterFileCleanupService;
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

    @GetMapping("/{id}/files/cleanup-preview")
    public ApiResponse<Map<String, Object>> cleanupFilesPreview(@PathVariable Long id) {
        AuthContext.requireAdmin();
        return ApiResponse.ok(semesterFileCleanupService.cleanup(id, false));
    }

    @PostMapping("/{id}/files/cleanup")
    public ApiResponse<Map<String, Object>> cleanupFiles(@PathVariable Long id,
                                                         @RequestBody(required = false) CleanupFilesRequest request) {
        AuthContext.requireAdmin();
        return ApiResponse.ok(semesterFileCleanupService.cleanup(
                id,
                true,
                request != null && Boolean.TRUE.equals(request.allowUnrecoverable()),
                request == null ? null : request.confirmedSemesterName(),
                request == null ? null : request.previewToken()));
    }

    public record SemesterRequest(String name) {
    }

    public record CleanupFilesRequest(Boolean allowUnrecoverable,
                                      String confirmedSemesterName,
                                      String previewToken) {
    }
}
