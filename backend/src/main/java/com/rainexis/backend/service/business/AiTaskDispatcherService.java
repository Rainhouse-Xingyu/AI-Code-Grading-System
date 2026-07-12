package com.rainexis.backend.service.business;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rainexis.backend.entity.TAiTask;
import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.mapper.TAiTaskMapper;
import com.rainexis.backend.mapper.TSubmissionMapper;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AiTaskDispatcherService {
    private final TAiTaskMapper taskMapper;
    private final TSubmissionMapper submissionMapper;
    private final AiScoringService aiScoringService;
    private final ExecutorService executor;
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private final boolean dispatcherEnabled;
    private final int maxConcurrentTasks;
    private final int runningTimeoutMinutes;
    private final RuntimeConfigService runtimeConfigService;

    public AiTaskDispatcherService(TAiTaskMapper taskMapper,
                                   TSubmissionMapper submissionMapper,
                                   AiScoringService aiScoringService,
                                   @Value("${app.ai.dispatcher-enabled}") boolean dispatcherEnabled,
                                   @Value("${app.ai.max-concurrent-tasks}") int maxConcurrentTasks,
                                   @Value("${app.ai.running-timeout-minutes}") int runningTimeoutMinutes,
                                   RuntimeConfigService runtimeConfigService) {
        this.taskMapper = taskMapper;
        this.submissionMapper = submissionMapper;
        this.aiScoringService = aiScoringService;
        this.dispatcherEnabled = dispatcherEnabled;
        this.maxConcurrentTasks = Math.max(1, maxConcurrentTasks);
        this.runningTimeoutMinutes = Math.max(5, runningTimeoutMinutes);
        this.runtimeConfigService = runtimeConfigService;
        this.executor = Executors.newFixedThreadPool(this.maxConcurrentTasks);
    }

    @Scheduled(fixedDelayString = "${app.ai.dispatch-interval-ms}")
    public void dispatchPendingTasks() {
        if (!dispatcherEnabled()) {
            return;
        }
        recoverStaleRunningTasks();
        int slots = maxConcurrentTasks() - activeTasks.get();
        if (slots <= 0) {
            return;
        }
        List<TAiTask> pending = taskMapper.selectList(new LambdaQueryWrapper<TAiTask>()
                .eq(TAiTask::getStatus, "pending")
                .orderByAsc(TAiTask::getCreatedAt)
                .last("limit " + slots));
        for (TAiTask task : pending) {
            if (!claim(task)) {
                continue;
            }
            activeTasks.incrementAndGet();
            executor.submit(() -> {
                try {
                    aiScoringService.logTask(task, "INFO", "后台调度器已领取任务，进入模型评分", 0);
                    aiScoringService.processTask(task.getId());
                } catch (Exception ignored) {
                    // processTask 已负责写失败状态和错误日志，这里只保证并发计数释放。
                } finally {
                    activeTasks.decrementAndGet();
                }
            });
        }
    }

    public int activeTasks() {
        return activeTasks.get();
    }

    public int maxConcurrentTasks() {
        return Math.min(maxConcurrentTasks, Math.max(1, runtimeConfigService.getInt("AI_MAX_CONCURRENT_TASKS", maxConcurrentTasks)));
    }

    private boolean claim(TAiTask task) {
        TAiTask update = new TAiTask();
        update.setStatus("running");
        update.setStartTime(LocalDateTime.now());
        int updated = taskMapper.update(update, new LambdaQueryWrapper<TAiTask>()
                .eq(TAiTask::getId, task.getId())
                .eq(TAiTask::getStatus, "pending"));
        if (updated <= 0) {
            return false;
        }
        TSubmission submission = submissionMapper.selectById(task.getSubmissionId());
        if (submission != null && !"scoring".equals(submission.getStatus())) {
            submission.setStatus("scoring");
            submissionMapper.updateById(submission);
        }
        task.setStatus("running");
        task.setStartTime(update.getStartTime());
        return true;
    }

    private void recoverStaleRunningTasks() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(runningTimeoutMinutes());
        List<TAiTask> staleTasks = taskMapper.selectList(new LambdaQueryWrapper<TAiTask>()
                .eq(TAiTask::getStatus, "running")
                .lt(TAiTask::getStartTime, cutoff));
        for (TAiTask task : staleTasks) {
            task.setStatus("pending");
            task.setStartTime(null);
            task.setErrorMessage("任务运行超时，已重新排队");
            taskMapper.updateById(task);
            aiScoringService.logTask(task, "WARN", "任务运行超时，后台调度器已重新排队", 0);
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    private boolean dispatcherEnabled() {
        return runtimeConfigService.getBoolean("AI_DISPATCHER_ENABLED", dispatcherEnabled);
    }

    private int runningTimeoutMinutes() {
        return Math.max(5, runtimeConfigService.getInt("AI_RUNNING_TIMEOUT_MINUTES", runningTimeoutMinutes));
    }
}
