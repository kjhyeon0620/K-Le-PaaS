package klepaas.backend.ai.diagnosis;

import klepaas.backend.ai.llm.LlmClient;
import klepaas.backend.ai.exception.AiProcessingException;
import klepaas.backend.deployment.dto.DeploymentDetailResponse;
import klepaas.backend.deployment.dto.DeploymentLogResponse;
import klepaas.backend.deployment.dto.DeploymentResponse;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.entity.FailureKind;
import klepaas.backend.deployment.service.DeploymentDetailService;
import klepaas.backend.deployment.service.DeploymentService;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.global.exception.InvalidRequestException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 진단 서비스 (#100): 소유권·상태 검사 전에는 모델을 호출하지 않는다. */
class DeploymentDiagnosisServiceTest {

    private final DeploymentDetailService details = mock(DeploymentDetailService.class);
    private final DeploymentService deployments = mock(DeploymentService.class);
    private final LlmClient gemini = mock(LlmClient.class);
    private final DeploymentDiagnosisService service =
            new DeploymentDiagnosisService(details, deployments, gemini,
                    JsonMapper.builder().propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE).build());

    private DeploymentDetailResponse detail(DeploymentStatus status) {
        var deployment = new DeploymentResponse(7L, 1L, "owner/app", "main", "abc1234", "img:v1", status,
                "rollout 실패: CrashLoopBackOff password=hunter2", null, null, null, null, null, null, null,
                FailureKind.CRASH_LOOP);
        return new DeploymentDetailResponse(deployment, null, null, null, null,
                DeploymentDetailResponse.Comparison.NO_PREVIOUS, List.of(), null);
    }

    @Test
    void rejectsForeignOrNonFailedDeploymentWithoutCallingModel() {
        when(details.getDetail(7L, 2L)).thenThrow(new EntityNotFoundException(ErrorCode.DEPLOYMENT_NOT_FOUND));
        assertThatThrownBy(() -> service.diagnose(7L, 2L)).isInstanceOf(EntityNotFoundException.class);

        when(details.getDetail(7L, 1L)).thenReturn(detail(DeploymentStatus.SUCCESS));
        assertThatThrownBy(() -> service.diagnose(7L, 1L)).isInstanceOf(InvalidRequestException.class);

        verifyNoInteractions(gemini, deployments);
    }

    @Test
    void sendsRedactedInputAndReturnsValidatedDiagnosis() {
        when(details.getDetail(7L, 1L)).thenReturn(detail(DeploymentStatus.FAILED));
        when(deployments.getDeploymentLogs(7L, 40, 1L)).thenReturn(new DeploymentLogResponse(7L, DeploymentStatus.FAILED,
                null, DeploymentLogResponse.Observation.AVAILABLE, null, 7L, List.of(), List.of()));
        when(gemini.completeJson(any())).thenReturn(text("""
                {"category":"ENV_CONFIG","root_cause":"required_env_missing","abstain":false,"summary":"요약",
                 "observed_facts":[],"hypotheses":[],"next_checks":[],"cited":["/detail/deployment/fail_reason","/logs/pods/0"]}"""));

        var result = service.diagnose(7L, 1L);

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).completeJson(prompt.capture());
        assertThat(prompt.getValue()).contains("password=[REDACTED]").doesNotContain("hunter2").contains("\"failure_kind\":\"CRASH_LOOP\"");
        assertThat(result.model()).isEqualTo("model-test");
        assertThat(result.promptVersion()).isEqualTo(DiagnosisPrompt.VERSION);
        assertThat(result.cited()).singleElement().satisfies(c -> assertThat(c.pointer()).isEqualTo("/detail/deployment/fail_reason"));
        assertThat(result.warnings()).singleElement().satisfies(w -> assertThat(w).contains("/logs/pods/0"));
        assertThat(result.abstain()).isFalse();
    }

    @Test
    void unreadableModelResponseIsAnErrorNotAGuess() {
        when(details.getDetail(7L, 1L)).thenReturn(detail(DeploymentStatus.UNKNOWN));
        when(deployments.getDeploymentLogs(7L, 40, 1L)).thenReturn(new DeploymentLogResponse(7L, DeploymentStatus.UNKNOWN,
                null, DeploymentLogResponse.Observation.UNAVAILABLE, "조회 실패", null, List.of(), List.of()));
        when(gemini.completeJson(any())).thenReturn(text("원인은 아마 env입니다"));

        assertThatThrownBy(() -> service.diagnose(7L, 1L)).isInstanceOfSatisfying(AiProcessingException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_PARSE_ERROR));
    }

    private static LlmClient.Response text(String body) {
        return new LlmClient.Response(body, "model-test", new LlmClient.Usage(100, 50, null, null), 5);
    }
}
