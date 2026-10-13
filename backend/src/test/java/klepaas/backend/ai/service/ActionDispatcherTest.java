package klepaas.backend.ai.service;

import klepaas.backend.ai.dto.ParsedIntent;
import klepaas.backend.ai.dto.FormattedResponseDto;
import klepaas.backend.ai.entity.Intent;
import klepaas.backend.ai.entity.RiskLevel;
import klepaas.backend.deployment.dto.DeploymentResponse;
import klepaas.backend.deployment.dto.DeploymentStatusResponse;
import klepaas.backend.deployment.dto.ScaleRequest;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.ai.service.KubectlService;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.service.DeploymentService;
import klepaas.backend.deployment.service.ResourceAccessService;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ActionDispatcherTest {

    @InjectMocks
    private ActionDispatcher actionDispatcher;

    @Mock
    private DeploymentService deploymentService;

    @Mock
    private KubectlService kubectlService;

    @Mock
    private DeploymentRepository deploymentRepository;

    @Mock
    private SourceRepositoryRepository sourceRepositoryRepository;

    @Mock
    private ResourceAccessService resourceAccessService;

    @Mock
    private klepaas.backend.deployment.service.RecoveryService recoveryService;

    @Test
    @DisplayName("ROLLBACK은 commit을 다시 빌드하지 않고 승인한 복구 계획 지문으로 이전 성공 배포를 복구한다 (#101)")
    void rollbackRecoversPreviousDeploymentWithApprovedPlan() {
        User owner = User.builder().name("owner").email("owner@example.com").role(Role.USER).build();
        ReflectionTestUtils.setField(owner, "id", 1L);
        SourceRepository repo = SourceRepository.builder().user(owner).owner("owner").repoName("app")
                .gitUrl("https://github.com/owner/app").cloudVendor(CloudVendor.ON_PREMISE).build();
        ReflectionTestUtils.setField(repo, "id", 7L);
        Deployment target = Deployment.builder().sourceRepository(repo).branchName("main").commitHash("aaaaaaa1").build();
        ReflectionTestUtils.setField(target, "id", 3L);
        given(sourceRepositoryRepository.findByOwnerAndRepoName("owner", "app")).willReturn(Optional.of(repo));
        given(recoveryService.findTarget(7L, null)).willReturn(Optional.of(target));
        var recovered = new DeploymentResponse(9L, 7L, "owner/app", "main", "aaaaaaa1", "ghcr.io/owner/app@sha256:111",
                DeploymentStatus.PENDING, null, null, null, null, null, 1L, 42L, null, null, 3L);
        given(recoveryService.recover(eq(3L), eq("plan-fp"), eq(1L), any()))
                .willReturn(new klepaas.backend.deployment.dto.CreateDeploymentResult(recovered, true));

        var result = (FormattedResponseDto) actionDispatcher.dispatch(
                new ParsedIntent(Intent.ROLLBACK, Map.of("owner", "owner", "repo", "app"), 0.9, "롤백"), 1L, 42L, "plan-fp");

        assertThat(result.type()).isEqualTo("rollback_execution");
        assertThat(result.message()).contains("배포 #3").contains("배포 ID: 9");
        var origin = ArgumentCaptor.forClass(klepaas.backend.deployment.service.DeploymentOrigin.class);
        verify(recoveryService).recover(eq(3L), eq("plan-fp"), eq(1L), origin.capture());
        assertThat(origin.getValue().commandLogId()).isEqualTo(42L);
        verify(deploymentService, org.mockito.Mockito.never()).createDeployment(any(), any(), any(), any());
    }

    @Test
    @DisplayName("에이전트 제안의 ROLLBACK은 commit이 아니라 지정한 배포 ID로 복구한다 (#102)")
    void rollbackUsesProposedTargetDeploymentId() {
        User owner = User.builder().name("owner").email("owner@example.com").role(Role.USER).build();
        ReflectionTestUtils.setField(owner, "id", 1L);
        SourceRepository repo = SourceRepository.builder().user(owner).owner("owner").repoName("app")
                .gitUrl("https://github.com/owner/app").cloudVendor(CloudVendor.ON_PREMISE).build();
        ReflectionTestUtils.setField(repo, "id", 7L);
        Deployment older = Deployment.builder().sourceRepository(repo).branchName("main").commitHash("aaaaaaa1").build();
        ReflectionTestUtils.setField(older, "id", 2L);
        given(sourceRepositoryRepository.findByOwnerAndRepoName("owner", "app")).willReturn(Optional.of(repo));
        given(resourceAccessService.requireDeployment(2L, 1L)).willReturn(older);
        var recovered = new DeploymentResponse(9L, 7L, "owner/app", "main", "aaaaaaa1", "img",
                DeploymentStatus.PENDING, null, null, null, null, null, 1L, 42L, null, null, 2L);
        given(recoveryService.recover(eq(2L), eq("plan-fp"), eq(1L), any()))
                .willReturn(new klepaas.backend.deployment.dto.CreateDeploymentResult(recovered, true));

        actionDispatcher.dispatch(new ParsedIntent(Intent.ROLLBACK, Map.of("owner", "owner", "repo", "app",
                "commit_hash", "aaaaaaa1", "target_deployment_id", 2), 1.0, ""), 1L, 42L, "plan-fp");

        verify(recoveryService).recover(eq(2L), eq("plan-fp"), eq(1L), any());
        verify(recoveryService, org.mockito.Mockito.never()).findTarget(any(), any());
    }

    @Test
    @DisplayName("DEPLOY는 HIGH 리스크")
    void classifyDeployAsHigh() {
        assertThat(actionDispatcher.classifyRisk(Intent.DEPLOY)).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    @DisplayName("SCALE은 MEDIUM 리스크")
    void classifyScaleAsMedium() {
        assertThat(actionDispatcher.classifyRisk(Intent.SCALE)).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    @DisplayName("RESTART은 MEDIUM 리스크")
    void classifyRestartAsMedium() {
        assertThat(actionDispatcher.classifyRisk(Intent.RESTART)).isEqualTo(RiskLevel.MEDIUM);
    }

    @Test
    @DisplayName("상태를 바꾸는 intent는 모두 승인 대상(LOW 아님)이고, 새 intent는 변경·조회 중 하나로 분류해야 한다 (#58)")
    void everyChangeIntentRequiresConfirmation() {
        var read = java.util.EnumSet.of(Intent.STATUS, Intent.LOGS, Intent.LIST_DEPLOYMENTS, Intent.LIST_REPOSITORIES,
                Intent.LIST_PODS, Intent.POD_STATUS, Intent.SERVICE_STATUS, Intent.DEPLOYMENT_STATUS, Intent.LIST_SERVICES,
                Intent.LIST_INGRESSES, Intent.LIST_NAMESPACES, Intent.LIST_ENDPOINTS, Intent.GET_SERVICE, Intent.GET_DEPLOYMENT,
                Intent.POD_LOGS, Intent.LIST_ROLLBACK, Intent.OVERVIEW, Intent.LIST_COMMANDS, Intent.COST_ANALYSIS,
                Intent.HELP, Intent.UNKNOWN);
        var change = klepaas.backend.ai.eval.NlpEval.CHANGE_INTENTS;

        for (Intent intent : Intent.values()) {
            assertThat(read.contains(intent) ^ change.contains(intent)).as("변경·조회 중 하나: " + intent).isTrue();
        }
        change.forEach(intent -> assertThat(actionDispatcher.classifyRisk(intent)).as(intent.name()).isNotEqualTo(RiskLevel.LOW));
    }

    @Test
    @DisplayName("STATUS는 LOW 리스크")
    void classifyStatusAsLow() {
        assertThat(actionDispatcher.classifyRisk(Intent.STATUS)).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("HELP는 LOW 리스크")
    void classifyHelpAsLow() {
        assertThat(actionDispatcher.classifyRisk(Intent.HELP)).isEqualTo(RiskLevel.LOW);
    }

    @Test
    @DisplayName("STATUS는 고정값 대신 실제 Pod 집계를 반환하고, 모두 준비되지 않으면 정상이 아니다")
    void dispatchStatus() {
        var parsedIntent = new ParsedIntent(Intent.STATUS, Map.of("deployment_id", 1), 0.9, "상태 확인");
        var statusResponse = new DeploymentStatusResponse(1L, DeploymentStatus.SUCCESS, null);
        given(deploymentService.getDeploymentStatus(1L, 1L)).willReturn(statusResponse);
        given(resourceAccessService.requireDeployment(1L, 1L)).willReturn(statusTarget());
        given(kubectlService.summarizeRepositoryPods(7L)).willReturn(Map.of(
                "namespace", "klepaas", "ready", "1/2", "restarts", 4, "all_ready", false));

        FormattedResponseDto result = (FormattedResponseDto) actionDispatcher.dispatch(parsedIntent, 1L);

        assertThat(result.message()).contains("SUCCESS").contains("1/2");
        assertThat(result.data().formatted()).isEqualTo(Map.of(
                "name", "owner-repo", "namespace", "klepaas", "status", "SUCCESS",
                "ready", "1/2", "restarts", 4, "age", "", "node", ""));
        assertThat(result.metadata()).isEqualTo(Map.of("namespace", "klepaas", "is_healthy", false));
        verify(deploymentService).getDeploymentStatus(1L, 1L);
    }

    @Test
    @DisplayName("STATUS에서 Pod 조회가 실패하면 unknown과 실패 사유를 표시한다")
    void dispatchStatusWithUnknownPods() {
        var parsedIntent = new ParsedIntent(Intent.STATUS, Map.of("deployment_id", 1), 0.9, "상태 확인");
        given(deploymentService.getDeploymentStatus(1L, 1L))
                .willReturn(new DeploymentStatusResponse(1L, DeploymentStatus.SUCCESS, null));
        given(resourceAccessService.requireDeployment(1L, 1L)).willReturn(statusTarget());
        given(kubectlService.summarizeRepositoryPods(7L)).willReturn(Map.of(
                "namespace", "klepaas", "ready", "unknown", "restarts", "unknown",
                "all_ready", false, "error", "connection refused"));

        FormattedResponseDto result = (FormattedResponseDto) actionDispatcher.dispatch(parsedIntent, 1L);

        assertThat(result.metadata()).isEqualTo(Map.of(
                "namespace", "klepaas", "is_healthy", false, "pod_status_error", "connection refused"));
    }

    private Deployment statusTarget() {
        SourceRepository repository = SourceRepository.builder()
                .owner("owner").repoName("repo").gitUrl("https://github.com/owner/repo")
                .cloudVendor(CloudVendor.NCP).build();
        ReflectionTestUtils.setField(repository, "id", 7L);
        return Deployment.builder().sourceRepository(repository).branchName("main").commitHash("abc1234").build();
    }

    @Test
    @DisplayName("LIST_DEPLOYMENTS는 고정 replica 값 없이 저장된 배포 이력만 반환한다")
    void dispatchListDeploymentsReturnsHistoryFields() {
        var parsedIntent = new ParsedIntent(Intent.LIST_DEPLOYMENTS, Map.of("repository_id", 7), 0.9, "배포 목록");
        var history = new DeploymentResponse(3L, 7L, "owner/repo", "main", "abc1234def",
                "ghcr.io/owner/repo:abc1234", DeploymentStatus.FAILED, "rollout timeout", null, null, null,
                null, null, null, null, null);
        given(deploymentService.getDeployments(eq(7L), any(), eq(1L))).willReturn(new PageImpl<>(List.of(history)));

        FormattedResponseDto result = (FormattedResponseDto) actionDispatcher.dispatch(parsedIntent, 1L);

        var expected = new java.util.LinkedHashMap<String, Object>();
        expected.put("id", 3L);
        expected.put("name", "deployment-3");
        expected.put("status", "FAILED");
        expected.put("branch", "main");
        expected.put("commit", "abc1234def");
        expected.put("image", "ghcr.io/owner/repo:abc1234");
        expected.put("fail_reason", "rollout timeout");
        expected.put("created_at", null);
        assertThat(result.data().formatted()).isEqualTo(List.of(expected));
    }

    @Test
    @DisplayName("SCALE 디스패치 시 DeploymentService.scaleDeployment 호출")
    void dispatchScale() {
        var parsedIntent = new ParsedIntent(Intent.SCALE, Map.of("deployment_id", 1, "replicas", 3), 0.9, "스케일링");

        User user = User.builder()
                .email("test@test.com")
                .name("tester")
                .role(Role.USER)
                .providerId("123")
                .build();
        SourceRepository repository = SourceRepository.builder()
                .user(user)
                .owner("owner")
                .repoName("repo")
                .gitUrl("https://github.com/owner/repo")
                .cloudVendor(CloudVendor.NCP)
                .build();
        Deployment deployment = Deployment.builder()
                .sourceRepository(repository)
                .branchName("main")
                .commitHash("abc1234")
                .build();
        given(resourceAccessService.requireDeployment(1L, 1L)).willReturn(deployment);
        given(deploymentService.scaleDeployment(eq(1L), any(), eq("NLP"), eq(1L))).willReturn(2);

        FormattedResponseDto result = (FormattedResponseDto) actionDispatcher.dispatch(parsedIntent, 1L);

        assertThat(result.message()).contains("3개 레플리카");
        assertThat(result.metadata()).isEqualTo(Map.of("owner", "owner", "repo", "repo", "old_replicas", 2, "new_replicas", 3, "status", "completed"));
    }

    @Test
    @DisplayName("SCALE에 replicas가 없으면 1로 추정하지 않고 0을 전달해 서비스 검증에 맡긴다")
    void dispatchScaleWithoutReplicasDoesNotDefaultToOne() {
        var parsedIntent = new ParsedIntent(Intent.SCALE, Map.of("deployment_id", 1), 0.9, "스케일링");
        given(deploymentService.scaleDeployment(eq(1L), any(), eq("NLP"), eq(1L)))
                .willThrow(new BusinessException(ErrorCode.INVALID_REQUEST));

        assertThatThrownBy(() -> actionDispatcher.dispatch(parsedIntent, 1L))
                .isInstanceOf(BusinessException.class);
        ArgumentCaptor<ScaleRequest> request = ArgumentCaptor.forClass(ScaleRequest.class);
        verify(deploymentService).scaleDeployment(eq(1L), request.capture(), eq("NLP"), eq(1L));
        assertThat(request.getValue().replicas()).isZero();
    }

    @Test
    @DisplayName("LIST_REPOSITORIES는 요청 사용자의 저장소만 조회해 실제 목록을 돌려준다 (#140)")
    void dispatchListRepositoriesReturnsOwnRepositories() {
        User owner = User.builder().name("owner").email("owner@example.test").role(Role.USER).build();
        ReflectionTestUtils.setField(owner, "id", 1L);
        SourceRepository repo = SourceRepository.builder().user(owner).owner("acme").repoName("sample-node")
                .gitUrl("https://github.com/acme/sample-node").cloudVendor(CloudVendor.NCP).build();
        ReflectionTestUtils.setField(repo, "id", 7L);
        ReflectionTestUtils.setField(repo, "createdAt", java.time.LocalDateTime.of(2026, 10, 14, 1, 2, 3));
        given(sourceRepositoryRepository.findAllByUserId(1L)).willReturn(List.of(repo));

        var parsedIntent = new ParsedIntent(Intent.LIST_REPOSITORIES, Map.of(), 0.9, "저장소 목록");
        FormattedResponseDto result = (FormattedResponseDto) actionDispatcher.dispatch(parsedIntent, 1L);

        assertThat(result.type()).isEqualTo("list_repositories");
        assertThat(result.message()).isEqualTo("저장소 목록: 총 1개");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.data().formatted();
        assertThat(items).containsExactly(Map.of("id", 7L, "full_name", "acme/sample-node", "owner", "acme",
                "repo_name", "sample-node", "created_at", "2026-10-14T01:02:03Z"));
        verify(sourceRepositoryRepository).findAllByUserId(1L);
        org.mockito.Mockito.verifyNoMoreInteractions(sourceRepositoryRepository);
    }

    @Test
    @DisplayName("저장소가 없으면 빈 목록과 등록 안내를 돌려준다 (#140)")
    void dispatchListRepositoriesEmpty() {
        given(sourceRepositoryRepository.findAllByUserId(2L)).willReturn(List.of());

        var parsedIntent = new ParsedIntent(Intent.LIST_REPOSITORIES, Map.of(), 0.9, "저장소 목록");
        FormattedResponseDto result = (FormattedResponseDto) actionDispatcher.dispatch(parsedIntent, 2L);

        assertThat(result.type()).isEqualTo("list_repositories");
        assertThat((List<?>) result.data().formatted()).isEmpty();
        assertThat(result.message()).contains("등록된 저장소가 없습니다");
    }

    @Test
    @DisplayName("HELP 디스패치 시 도움말 반환")
    void dispatchHelp() {
        var parsedIntent = new ParsedIntent(Intent.HELP, Map.of(), 1.0, "도움말");
        given(kubectlService.listCommands()).willReturn(
                FormattedResponseDto.of("list_commands", "사용 가능한 명령어 목록입니다.", "명령어", Map.of(), null)
        );
        FormattedResponseDto result = (FormattedResponseDto) actionDispatcher.dispatch(parsedIntent, 1L);

        assertThat(result.message()).contains("사용 가능한 명령어");
    }
}
