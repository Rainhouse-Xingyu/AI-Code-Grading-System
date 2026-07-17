package com.rainexis.backend.controller.api;

import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.security.AuthContext;
import com.rainexis.backend.service.business.FileStorageService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/files")
public class FileApiController {
    private final FileStorageService fileStorageService;

    public FileApiController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @GetMapping("/cleanup-preview")
    public ApiResponse<Map<String, Object>> cleanupPreview(@RequestParam(defaultValue = "180") int olderThanDays) {
        AuthContext.requireAdmin();
        return ApiResponse.ok(fileStorageService.cleanupOlderThan(olderThanDays, false));
    }

    @PostMapping("/cleanup")
    public ApiResponse<Map<String, Object>> cleanup(@RequestParam(defaultValue = "180") int olderThanDays) {
        AuthContext.requireAdmin();
        return ApiResponse.ok(fileStorageService.cleanupOlderThan(olderThanDays, true));
    }
}
