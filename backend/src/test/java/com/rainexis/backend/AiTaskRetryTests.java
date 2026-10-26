package com.rainexis.backend;

import com.rainexis.backend.entity.TAiTask;
import com.rainexis.backend.entity.TSubmission;
import com.rainexis.backend.mapper.TAiTaskMapper;
import com.rainexis.backend.mapper.TSubmissionMapper;
import com.rainexis.backend.service.business.AiScoringService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ai_retry;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "app.ai.dispatcher-enabled=true",
        "app.ai.dispatch-interval-ms=3600000",
        "APP_ENV_FILE=${java.io.tmpdir}/ai-retry-test-empty.env"
})
@Transactional
class AiTaskRetryTests {
    @Autowired private AiScoringService service;
    @Autowired private TAiTaskMapper taskMapper;
    @Autowired private TSubmissionMapper submissionMapper;

    @Test
    void retryClearsPreviousTimesAndErrorInDatabase() {
        TSubmission submission = new TSubmission();
        submission.setAssignmentId(1L);
        submission.setStudentId(1L);
        submission.setFileUrl("test-only.zip");
        submission.setStatus("partial_scored");
        submissionMapper.insert(submission);

        TAiTask task = new TAiTask();
        task.setAssignmentId(1L);
        task.setSubmissionId(submission.getId());
        task.setStatus("failed");
        task.setErrorMessage("old error");
        task.setRetryCount(0);
        task.setStartTime(LocalDateTime.now().minusMinutes(5));
        task.setEndTime(LocalDateTime.now().minusMinutes(4));
        taskMapper.insert(task);

        service.retryTask(task.getId());

        TAiTask saved = taskMapper.selectById(task.getId());
        assertThat(saved.getStatus()).isEqualTo("pending");
        assertThat(saved.getRetryCount()).isEqualTo(1);
        assertThat(saved.getErrorMessage()).isNull();
        assertThat(saved.getStartTime()).isNull();
        assertThat(saved.getEndTime()).isNull();
        assertThat(submissionMapper.selectById(submission.getId()).getStatus()).isEqualTo("scoring");
    }

    @Test
    void durationHandlesRunningAndLegacyInvertedTimes() {
        TAiTask task = new TAiTask();
        task.setStartTime(LocalDateTime.now().minusSeconds(2));
        long running = ReflectionTestUtils.invokeMethod(service, "durationMs", task);
        assertThat(running).isGreaterThanOrEqualTo(2000);
        task.setEndTime(task.getStartTime().minusMinutes(1));
        long inverted = ReflectionTestUtils.invokeMethod(service, "durationMs", task);
        assertThat(inverted).isZero();
    }
}
