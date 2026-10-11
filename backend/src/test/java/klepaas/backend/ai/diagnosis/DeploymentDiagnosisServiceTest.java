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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 진단 서비스 (#100): 소유권·상태 검사 전에는 모델을 호출하지 않는다. 결과 재사용·호출 생략 (#117). */
class DeploymentDiagnosisServiceTest {

    private final DeploymentDetailService details = mock(DeploymentDetailService.class);
    private final DeploymentService deployments = mock(DeploymentService.class);
    private final LlmClient gemini = mock(LlmClient.class);
    private final DeploymentDiagnosisRepository repository = mock(DeploymentDiagnosisRepository.class);
    private final klepaas.backend.ai.knowledge.KnowledgeService knowledge = mock(klepaas.backend.ai.knowledge.KnowledgeService.class);
    private final JsonMapper json =
            JsonMapper.builder().propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE).build();
    private final DeploymentDiagnosisService service =
            new DeploymentDiagnosisService(details, deployments, gemini, json, repository, knowledge);

    {
        when(gemini.model()).thenReturn("luna");
        when(repository.findFirstByDeploymentIdOrderByIdDesc(any())).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private DeploymentDetailResponse detail(DeploymentStatus status) {
        return detail(status, FailureKind.CRASH_LOOP);
    }

    private DeploymentDetailResponse detail(DeploymentStatus status, FailureKind kind) {
        var deployment = new DeploymentResponse(7L, 1L, "owner/app", "main", "abc1234", "img:v1", status,
                "rollout 실패: CrashLoopBackOff password=hunter2", null, null, null, null, null, null, null,
                kind);
        return new DeploymentDetailResponse(deployment, null, null, null, null,
                DeploymentDetailResponse.Comparison.NO_PREVIOUS, List.of(), null);
    }

    @Test
    void rejectsForeignOrNonFailedDeploymentWithoutCallingModel() {
        when(details.getDetail(7L, 2L)).thenThrow(new EntityNotFoundException(ErrorCode.DEPLOYMENT_NOT_FOUND));
        assertThatThrownBy(() -> service.diagnose(7L, 2L, false)).isInstanceOf(EntityNotFoundException.class);

        when(details.getDetail(7L, 1L)).thenReturn(detail(DeploymentStatus.SUCCESS));
        assertThatThrownBy(() -> service.diagnose(7L, 1L, false)).isInstanceOf(InvalidRequestException.class);

        verify(gemini, never()).completeJson(any());
        verifyNoInteractions(deployments, repository);
    }

    @Test
    void sendsRedactedInputAndReturnsValidatedDiagnosis() {
        when(details.getDetail(7L, 1L)).thenReturn(detail(DeploymentStatus.FAILED));
        when(deployments.getDeploymentLogs(7L, 40, 1L)).thenReturn(new DeploymentLogResponse(7L, DeploymentStatus.FAILED,
                null, DeploymentLogResponse.Observation.AVAILABLE, null, 7L, List.of(), List.of()));
        when(gemini.completeJson(any())).thenReturn(text("""
                {"category":"ENV_CONFIG","root_cause":"required_env_missing","abstain":false,"summary":"요약",
                 "observed_facts":[],"hypotheses":[],"next_checks":[],"cited":["/detail/deployment/fail_reason","/logs/pods/0"]}"""));

        var result = service.diagnose(7L, 1L, false);

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).completeJson(prompt.capture());
        assertThat(prompt.getValue()).contains("password=[REDACTED]").doesNotContain("hunter2").contains("\"failure_kind\":\"CRASH_LOOP\"");
        assertThat(result.model()).isEqualTo("model-test");
        assertThat(result.promptVersion()).isEqualTo(DiagnosisPrompt.VERSION);
        assertThat(result.cited()).singleElement().satisfies(c -> assertThat(c.pointer()).isEqualTo("/detail/deployment/fail_reason"));
        assertThat(result.warnings()).singleElement().satisfies(w -> assertThat(w).contains("/logs/pods/0"));
        assertThat(result.abstain()).isFalse();
        assertThat(result.reused()).isFalse();
        var saved = ArgumentCaptor.forClass(DeploymentDiagnosis.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRequestedModel()).isEqualTo("luna");
        assertThat(saved.getValue().getInputTokens()).isEqualTo(100L);
        assertThat(saved.getValue().getResult()).contains("required_env_missing").doesNotContain("hunter2");
    }

    @Test
    void reusesLatestResultWithoutLogsOrModelUntilRefreshOrModelChange() {
        when(details.getDetail(7L, 1L)).thenReturn(detail(DeploymentStatus.FAILED));
        when(repository.findFirstByDeploymentIdOrderByIdDesc(7L)).thenReturn(Optional.of(new DeploymentDiagnosis(7L, "luna",
                DiagnosisPrompt.VERSION, json.writeValueAsString(stored("ENV_CONFIG")), 100L, null, 50L, null, 5L)));

        var reused = service.diagnose(7L, 1L, false);

        assertThat(reused.reused()).isTrue();
        assertThat(reused.category()).isEqualTo("ENV_CONFIG");
        verifyNoInteractions(deployments);
        verify(gemini, never()).completeJson(any());
        verify(repository, never()).save(any());

        // refresh와 설정 모델 변경은 새로 진단한다
        when(deployments.getDeploymentLogs(7L, 40, 1L)).thenReturn(new DeploymentLogResponse(7L, DeploymentStatus.FAILED,
                null, DeploymentLogResponse.Observation.AVAILABLE, null, 7L, List.of(), List.of()));
        when(gemini.completeJson(any())).thenReturn(text("{\"abstain\":true}"));
        assertThat(service.diagnose(7L, 1L, true).reused()).isFalse();
        when(gemini.model()).thenReturn("other-model");
        assertThat(service.diagnose(7L, 1L, false).reused()).isFalse();
        verify(gemini, times(2)).completeJson(any());
        verify(repository, times(2)).save(any());
    }

    @Test
    void noEvidenceAbstainsWithoutCallingModelAndIsSaved() {
        when(details.getDetail(7L, 1L)).thenReturn(detail(DeploymentStatus.FAILED, FailureKind.ROLLOUT_TIMEOUT));
        when(deployments.getDeploymentLogs(7L, 40, 1L)).thenReturn(new DeploymentLogResponse(7L, DeploymentStatus.FAILED,
                null, DeploymentLogResponse.Observation.UNAVAILABLE, "조회 실패", null, List.of(), List.of()));

        var result = service.diagnose(7L, 1L, false);

        assertThat(result.abstain()).isTrue();
        assertThat(result.model()).isEqualTo("rule");
        assertThat(result.promptVersion()).isEqualTo("no-evidence-v1");
        assertThat(result.warnings()).singleElement().satisfies(w -> assertThat(w).contains("모델을 호출하지 않았습니다"));
        verify(gemini, never()).completeJson(any());
        var saved = ArgumentCaptor.forClass(DeploymentDiagnosis.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRequestedModel()).isEqualTo("rule");
        assertThat(saved.getValue().getInputTokens()).isNull();
    }

    /** 호출 생략 조건은 #99 사례 중 증거 부족 2개에만 해당한다 */
    @Test
    void noEvidenceMatchesOnlyInsufficientEvalCases() throws Exception {
        for (var c : klepaas.backend.diagnosis.eval.DiagnosisEval.loadCases()) {
            var d = json.readValue(c.input().get("detail").toString(), DeploymentDetailResponse.class);
            var l = json.readValue(c.input().get("logs").toString(), DeploymentLogResponse.class);
            assertThat(DeploymentDiagnosisService.noEvidence(d, l)).as(c.id()).isEqualTo(c.group().equals("INSUFFICIENT"));
        }
    }

    @Test
    void retrievedKnowledgeGoesIntoInputAndCitedKnowledgeIsSeparatedFromObservations() {
        when(details.getDetail(7L, 1L)).thenReturn(detail(DeploymentStatus.FAILED));
        when(deployments.getDeploymentLogs(7L, 40, 1L)).thenReturn(new DeploymentLogResponse(7L, DeploymentStatus.FAILED,
                null, DeploymentLogResponse.Observation.AVAILABLE, null, 7L, List.of(), List.of()));
        var doc = new klepaas.backend.ai.knowledge.KnowledgeDtos.KnowledgeItem("DOCUMENT", "doc:3", "운영 문서", "owner/app",
                "rev 2", java.time.LocalDateTime.of(2026, 10, 1, 9, 0), "E_BOOT_17은 ADMIN_PASSWORD가 비었을 때 난다", 0.61);
        var platform = new klepaas.backend.ai.knowledge.KnowledgeDtos.KnowledgeItem("PLATFORM", "platform:image-pull.md",
                "이미지", "platform", "sha 1", null, "이미지 pull 실패", 0.31);
        when(knowledge.search(eq(1L), eq("owner/app"), eq(7L), any())).thenReturn(List.of(doc, platform));
        when(gemini.completeJson(any())).thenReturn(text("""
                {"category":"ENV_CONFIG","root_cause":"required_env_missing","abstain":false,"summary":"요약",
                 "observed_facts":[],"hypotheses":[],"next_checks":[],
                 "cited":["/detail/deployment/fail_reason","/knowledge/0/text","/knowledge/0"]}"""));

        var result = service.diagnose(7L, 1L, false);

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).completeJson(prompt.capture());
        assertThat(prompt.getValue()).contains("\"knowledge\":[").contains("E_BOOT_17").contains("지식 규칙");
        assertThat(result.promptVersion()).isEqualTo(DiagnosisPrompt.RAG_VERSION);
        assertThat(result.cited()).extracting(DiagnosisPrompt.Citation::pointer).containsExactly("/detail/deployment/fail_reason");
        assertThat(result.knowledge()).singleElement().satisfies(k -> {
            assertThat(k.ref()).isEqualTo("doc:3");
            assertThat(k.version()).isEqualTo("rev 2");
            assertThat(k.excerpt()).contains("E_BOOT_17");
        });
    }

    @Test
    void knowledgeSearchFailureFallsBackToPlainDiagnosisWithWarning() {
        when(details.getDetail(7L, 1L)).thenReturn(detail(DeploymentStatus.FAILED));
        when(deployments.getDeploymentLogs(7L, 40, 1L)).thenReturn(new DeploymentLogResponse(7L, DeploymentStatus.FAILED,
                null, DeploymentLogResponse.Observation.AVAILABLE, null, 7L, List.of(), List.of()));
        when(knowledge.search(any(), any(), any(), any())).thenThrow(new AiProcessingException(ErrorCode.AI_API_ERROR, "x"));
        when(gemini.completeJson(any())).thenReturn(text("{\"abstain\":true}"));

        var result = service.diagnose(7L, 1L, false);

        var prompt = ArgumentCaptor.forClass(String.class);
        verify(gemini).completeJson(prompt.capture());
        assertThat(prompt.getValue()).doesNotContain("knowledge").doesNotContain("지식 규칙");
        assertThat(result.promptVersion()).isEqualTo(DiagnosisPrompt.VERSION);
        assertThat(result.warnings()).contains("지식 검색에 실패해 지식 없이 진단했습니다");
        assertThat(result.knowledge()).isEmpty();
    }

    private static DeploymentDiagnosisResponse stored(String category) {
        return new DeploymentDiagnosisResponse(7L, "luna-2026", DiagnosisPrompt.VERSION, category, "required_env_missing",
                false, "요약", List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false, null);
    }

    @Test
    void unreadableModelResponseIsAnErrorNotAGuess() {
        when(details.getDetail(7L, 1L)).thenReturn(detail(DeploymentStatus.UNKNOWN));
        when(deployments.getDeploymentLogs(7L, 40, 1L)).thenReturn(new DeploymentLogResponse(7L, DeploymentStatus.UNKNOWN,
                null, DeploymentLogResponse.Observation.UNAVAILABLE, "조회 실패", null, List.of(), List.of()));
        when(gemini.completeJson(any())).thenReturn(text("원인은 아마 env입니다"));

        assertThatThrownBy(() -> service.diagnose(7L, 1L, false)).isInstanceOfSatisfying(AiProcessingException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.AI_PARSE_ERROR));
        verify(repository, never()).save(any());
    }

    private static LlmClient.Response text(String body) {
        return new LlmClient.Response(body, "model-test", new LlmClient.Usage(100, 50, null, null), 5);
    }
}
