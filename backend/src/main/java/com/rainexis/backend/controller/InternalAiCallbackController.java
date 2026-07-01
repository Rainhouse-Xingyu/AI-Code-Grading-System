package com.rainexis.backend.controller;

import com.rainexis.backend.common.ApiResponse;
import com.rainexis.backend.entity.TAiTask;
import com.rainexis.backend.service.business.AiScoringService;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部AI服务回调控制器
 * 接收异步AI评分完成后的回调通知，将结果写入数据库
 * 路径 /internal 对外不可访问（由网关/防火墙限制）
 */
@RestController
@RequestMapping("/internal")
public class InternalAiCallbackController {
    private final AiScoringService aiScoringService;

    public InternalAiCallbackController(AiScoringService aiScoringService) {
        this.aiScoringService = aiScoringService;
    }

    /** 接收AI服务的评分结果回调 */
    @PostMapping("/ai-callback")
    public ApiResponse<TAiTask> callback(@RequestBody CallbackRequest request) {
        return ApiResponse.ok(aiScoringService.handleCallback(
                request.taskId(),
                request.status(),
                request.result(),
                request.errorMessage()
        ));
    }

    /** 回调请求格式 */
    public record CallbackRequest(Long taskId, Long submissionId, String status, Map<String, Object> result,
                                  String errorMessage) {
    }
}
