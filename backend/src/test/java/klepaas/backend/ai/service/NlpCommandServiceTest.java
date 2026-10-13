package klepaas.backend.ai.service;

import klepaas.backend.ai.llm.LlmClient;
import klepaas.backend.ai.dto.NlpCommandRequest;
import klepaas.backend.ai.dto.NlpCommandResponse;
import klepaas.backend.ai.dto.NlpConfirmRequest;
import klepaas.backend.ai.dto.FormattedResponseDto;
import klepaas.backend.ai.dto.ApprovalTarget;
import klepaas.backend.deployment.entity.BuildStrategy;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.ai.entity.*;
import klepaas.backend.ai.repository.CommandLogRepository;
import klepaas.backend.ai.repository.ConversationSessionRepository;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;
import klepaas.backend.global.exception.EntityNotFoundException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NlpCommandServiceTest {

    @Mock private LlmClient nlpLlm;
    @Mock private IntentParser intentParser;
    @Mock private ActionDispatcher actionDispatcher;
    @Mock private ApprovalTargetResolver approvalTargets;
    @Mock private CommandLogRepository commandLogRepository;
    @Mock private CommandConfirmationService confirmations;
    @Mock private ConversationSessionRepository sessionRepository;
    @Mock private UserRepository userRepository;
    @Mock private klepaas.backend.deployment.service.ResourceAccessService resourceAccessService;

    private NlpCommandService nlpCommandService;
    private User testUser;
    private ConversationSession testSession;

    @BeforeEach
    void setUp() {
        var systemPromptResource = new ByteArrayResource("테스트 시스템 프롬프트".getBytes());
        nlpCommandService = new NlpCommandService(
                nlpLlm, intentParser, actionDispatcher, approvalTargets,
                commandLogRepository, confirmations, sessionRepository, userRepository, resourceAccessService,
                systemPromptResource
        );

        testUser = User.builder()
                .name("testuser")
                .email("test@test.com")
                .role(Role.USER)
                .providerId("12345")
                .build();
        ReflectionTestUtils.setField(testUser, "id", 1L);

        testSession = new ConversationSession(testUser);
    }

    private klepaas.backend.deployment.entity.Deployment proposalTarget() {
        var repo = klepaas.backend.deployment.entity.SourceRepository.builder().user(testUser).owner("owner").repoName("app")
                .gitUrl("https://example.test/owner/app").cloudVendor(klepaas.backend.deployment.entity.CloudVendor.ON_PREMISE).build();
        ReflectionTestUtils.setField(repo, "id", 7L);
        var target = klepaas.backend.deployment.entity.Deployment.builder().sourceRepository(repo).branchName("main")
                .commitHash("abcdef1234").build();
        ReflectionTestUtils.setField(target, "id", 3L);
        return target;
    }

    @Test
    @DisplayName("복구 제안은 지정 배포의 계획 지문을 고정한 승인 대기 명령만 만들고 실행하지 않는다 (#102)")
    void recoveryProposalCreatesPendingCommandOnly() {
        given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
        given(resourceAccessService.requireDeployment(3L, 1L)).willReturn(proposalTarget());
        given(actionDispatcher.classifyRisk(Intent.ROLLBACK)).willReturn(RiskLevel.HIGH);
        var target = new ApprovalTarget(7L, "owner/app", "main", "abcdef1234", BuildStrategy.PREBUILT_IMAGE, "img@sha256:1",
                1, 1, 8080, List.of(), "planfp12", 3L, List.of());
        given(approvalTargets.resolve(eq(Intent.ROLLBACK), any(), eq(1L)))
                .willReturn(Optional.of(new ApprovalTargetResolver.Resolved(target, "planfp1234")));

        NlpCommandResponse response = nlpCommandService.proposeRecovery(1L, 3L, "readiness 실패 후 이전 이미지로");

        var saved = org.mockito.ArgumentCaptor.forClass(CommandLog.class);
        verify(commandLogRepository).save(saved.capture());
        assertThat(saved.getValue().getInterpretedIntent()).isEqualTo(Intent.ROLLBACK);
        assertThat(saved.getValue().getStatus()).isEqualTo(CommandStatus.PENDING);
        assertThat(saved.getValue().getApprovedConfigFingerprint()).isEqualTo("planfp1234");
        assertThat(saved.getValue().getIntentArgs()).contains("\"target_deployment_id\":3");
        assertThat(saved.getValue().getRawCommand()).isEqualTo("복구 제안: 배포 #3 (abcdef1) — readiness 실패 후 이전 이미지로");
        assertThat(response.requiresConfirmation()).isTrue();
        assertThat(response.approvalTarget()).isEqualTo(target);
        verify(actionDispatcher, never()).dispatch(any(), any(), any());
        verify(actionDispatcher, never()).dispatch(any(), any(), any(), any());
        verifyNoInteractions(nlpLlm);
    }

    @Test
    @DisplayName("실행할 수 없는 복구 계획과 권한 밖 배포는 제안을 만들지 않는다 (#102)")
    void recoveryProposalRejectsBlockedOrForeignTarget() {
        given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
        given(resourceAccessService.requireDeployment(3L, 1L)).willReturn(proposalTarget());
        var blocked = new ApprovalTarget(7L, "owner/app", "main", "abcdef1234", null, "img", 0, 0, 0, List.of(), null, 3L,
                List.of("대상 배포의 설정 기록이 없습니다 (#95 이전 배포)"));
        given(approvalTargets.resolve(eq(Intent.ROLLBACK), any(), eq(1L)))
                .willReturn(Optional.of(new ApprovalTargetResolver.Resolved(blocked, null)));
        assertThatThrownBy(() -> nlpCommandService.proposeRecovery(1L, 3L, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECOVERY_BLOCKED);
                    assertThat(e.getMessage()).contains("설정 기록이 없습니다");
                });

        given(resourceAccessService.requireDeployment(4L, 1L)).willThrow(new EntityNotFoundException(ErrorCode.DEPLOYMENT_NOT_FOUND));
        assertThatThrownBy(() -> nlpCommandService.proposeRecovery(1L, 4L, null)).isInstanceOf(EntityNotFoundException.class);
        verify(commandLogRepository, never()).save(any());
    }

    @Test
    @DisplayName("모델에는 지시문과 사용자 입력을 이어 붙인 문자열 하나를 보낸다 (#58 평가와 같은 문자열, #136)")
    void sendsSamePromptStringAsEvaluation() {
        given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
        given(sessionRepository.save(any())).willReturn(testSession);
        given(nlpLlm.completeJson(any())).willReturn(llmResponse("test"));
        given(intentParser.parse("test")).willReturn(
                new klepaas.backend.ai.dto.ParsedIntent(Intent.HELP, java.util.Map.of(), 1.0, "도움말"));
        given(actionDispatcher.classifyRisk(Intent.HELP)).willReturn(RiskLevel.LOW);
        given(commandLogRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        nlpCommandService.processCommand(1L, new NlpCommandRequest("도움말", null));

        verify(nlpLlm).completeJson("테스트 시스템 프롬프트\n\n사용자 입력: 도움말");
    }

    @Test
    @DisplayName("모델 호출이 실패하면 AI_001로 끝나고 명령 기록·실행이 없다 (#136, 자동 대체 없음)")
    void modelFailureLeavesNoCommand() {
        given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
        given(sessionRepository.save(any())).willReturn(testSession);
        given(nlpLlm.completeJson(any())).willThrow(new klepaas.backend.ai.exception.AiProcessingException(
                klepaas.backend.global.exception.ErrorCode.AI_API_ERROR, "openai API 키가 설정되지 않았습니다"));

        assertThatThrownBy(() -> nlpCommandService.processCommand(1L, new NlpCommandRequest("배포 3번 재시작", null)))
                .isInstanceOf(klepaas.backend.ai.exception.AiProcessingException.class);
        verify(commandLogRepository, never()).save(any());
        verifyNoInteractions(intentParser, actionDispatcher);
    }

    @Test
    @DisplayName("대상이 빠진 변경 명령은 승인 대기 없이 되묻고 FAILED로 기록한다 (#135)")
    void changeCommandWithoutTargetAsksBack() {
        given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
        given(sessionRepository.save(any())).willReturn(testSession);
        given(nlpLlm.completeJson(any())).willReturn(llmResponse("test"));
        given(intentParser.parse("test")).willReturn(new klepaas.backend.ai.dto.ParsedIntent(Intent.RESTART,
                java.util.Map.of("app_name", "sample-node"), 0.9, "재시작합니다"));
        given(actionDispatcher.classifyRisk(Intent.RESTART)).willReturn(RiskLevel.MEDIUM);
        var saved = new java.util.concurrent.atomic.AtomicReference<CommandLog>();
        given(commandLogRepository.save(any())).willAnswer(inv -> {
            saved.set(inv.getArgument(0));
            return inv.getArgument(0);
        });

        NlpCommandResponse response = nlpCommandService.processCommand(1L, new NlpCommandRequest("sample-node 재시작해줘", null));

        assertThat(response.requiresConfirmation()).isFalse();
        assertThat(response.result()).isNull();
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.MEDIUM);
        assertThat(response.message()).contains("대상 배포 ID");
        assertThat(saved.get().getStatus()).isEqualTo(CommandStatus.FAILED);
        assertThat(saved.get().getErrorMessage()).isEqualTo("대상 누락: deployment_id");
        assertThat(saved.get().isRequiresConfirmation()).isFalse();
        verify(actionDispatcher, never()).dispatch(any(), anyLong(), any());
        verifyNoInteractions(approvalTargets);
    }

    @Test
    @DisplayName("LOW 리스크 명령은 즉시 실행")
    void processLowRiskCommand() {
        given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
        given(sessionRepository.save(any())).willReturn(testSession);
        given(nlpLlm.completeJson(any())).willReturn(llmResponse("test"));
        given(intentParser.parse("test")).willReturn(
                new klepaas.backend.ai.dto.ParsedIntent(Intent.HELP, java.util.Map.of(), 1.0, "도움말"));
        given(actionDispatcher.classifyRisk(Intent.HELP)).willReturn(RiskLevel.LOW);
        given(actionDispatcher.dispatch(any(), eq(1L), any())).willReturn("도움말 결과");
        given(commandLogRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        NlpCommandResponse response = nlpCommandService.processCommand(1L,
                new NlpCommandRequest("도움말", null));

        assertThat(response.requiresConfirmation()).isFalse();
        assertThat(response.result()).isEqualTo("도움말 결과");
        assertThat(response.intent()).isEqualTo(Intent.HELP);
    }

    @Test
    @DisplayName("HIGH 리스크 명령은 확인 대기")
    void processHighRiskCommand() {
        given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
        given(sessionRepository.save(any())).willReturn(testSession);
        given(nlpLlm.completeJson(any())).willReturn(llmResponse("test"));
        given(intentParser.parse("test")).willReturn(
                new klepaas.backend.ai.dto.ParsedIntent(Intent.DEPLOY,
                        java.util.Map.of("repository_id", 1, "branch_name", "main"), 0.95, "배포합니다"));
        given(actionDispatcher.classifyRisk(Intent.DEPLOY)).willReturn(RiskLevel.HIGH);
        given(commandLogRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        NlpCommandResponse response = nlpCommandService.processCommand(1L,
                new NlpCommandRequest("프로젝트 배포해줘", null));

        assertThat(response.requiresConfirmation()).isTrue();
        assertThat(response.result()).isNull();
        assertThat(response.riskLevel()).isEqualTo(RiskLevel.HIGH);
        verify(actionDispatcher, never()).dispatch(any(), anyLong(), any());
        assertThat(response.approvalTarget()).isNull(); // 대상을 확인하지 못하면 지문 없이 저장
    }

    @Test
    @DisplayName("승인이 필요한 배포 명령은 생성 시 설정 지문을 저장하고 승인 대상을 보여 준다")
    void processHighRiskCommandPinsApprovalTarget() {
        given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
        given(sessionRepository.save(any())).willReturn(testSession);
        given(nlpLlm.completeJson(any())).willReturn(llmResponse("test"));
        given(intentParser.parse("test")).willReturn(new klepaas.backend.ai.dto.ParsedIntent(Intent.DEPLOY,
                java.util.Map.of("repository_id", 1), 0.95, "배포합니다"));
        given(actionDispatcher.classifyRisk(Intent.DEPLOY)).willReturn(RiskLevel.HIGH);
        given(approvalTargets.resolve(eq(Intent.DEPLOY), any(), eq(1L))).willReturn(Optional.of(resolved("fp-approved")));
        var saved = new java.util.concurrent.atomic.AtomicReference<CommandLog>();
        given(commandLogRepository.save(any())).willAnswer(inv -> {
            saved.set(inv.getArgument(0));
            return inv.getArgument(0);
        });

        NlpCommandResponse response = nlpCommandService.processCommand(1L, new NlpCommandRequest("배포해줘", null));

        assertThat(saved.get().getApprovedConfigFingerprint()).isEqualTo("fp-approved");
        assertThat(response.approvalTarget().envNames()).containsExactly("SAMPLE_GREETING");
        verify(actionDispatcher, never()).dispatch(any(), anyLong(), any());
    }

    @Test
    @DisplayName("확인 시 명령 실행")
    void confirmCommandExecutes() {
        CommandLog commandLog = CommandLog.builder()
                .user(testUser)
                .rawCommand("배포해줘")
                .interpretedIntent(Intent.DEPLOY)
                .intentArgs("{\"repository_id\":1,\"branch_name\":\"main\"}")
                .riskLevel(RiskLevel.HIGH)
                .requiresConfirmation(true)
                .session(testSession)
                .build();

        commandLog.pinApprovedConfig("fp-approved");
        given(confirmations.claim(1L, 1L)).willReturn(commandLog);
        given(approvalTargets.isPinned(Intent.DEPLOY)).willReturn(true);
        given(approvalTargets.resolve(eq(Intent.DEPLOY), any(), eq(1L))).willReturn(Optional.of(resolved("fp-approved")));
        given(actionDispatcher.dispatch(any(), eq(1L), any(), eq("fp-approved")))
                .willReturn("배포가 시작되었습니다");

        NlpCommandResponse response = nlpCommandService.confirmCommand(1L,
                new NlpConfirmRequest(1L, true));

        assertThat(response.result()).isEqualTo("배포가 시작되었습니다");
        assertThat(response.approvalTarget().configFingerprint()).isEqualTo("fp-appro");
        verify(actionDispatcher).dispatch(any(), eq(1L), any(), eq("fp-approved"));
    }

    @Test
    @DisplayName("승인 대기 중 설정이 바뀌었거나 승인 지문이 없으면 실행하지 않고 409")
    void confirmRejectsChangedOrUnpinnedApprovalTarget() {
        given(approvalTargets.isPinned(Intent.DEPLOY)).willReturn(true);
        given(approvalTargets.resolve(eq(Intent.DEPLOY), any(), eq(1L))).willReturn(Optional.of(resolved("fp-changed")));
        for (String pinned : new String[]{"fp-approved", null}) {
            CommandLog commandLog = CommandLog.builder().user(testUser).rawCommand("배포해줘")
                    .interpretedIntent(Intent.DEPLOY).intentArgs("{\"repository_id\":1}")
                    .riskLevel(RiskLevel.HIGH).requiresConfirmation(true).session(testSession).build();
            commandLog.pinApprovedConfig(pinned);
            given(confirmations.claim(1L, 1L)).willReturn(commandLog);

            assertThatThrownBy(() -> nlpCommandService.confirmCommand(1L, new NlpConfirmRequest(1L, true)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.APPROVAL_TARGET_CHANGED));
        }
        verify(confirmations, times(2)).fail(any(), eq(ErrorCode.APPROVAL_TARGET_CHANGED.getMessage()));
        verify(actionDispatcher, never()).dispatch(any(), anyLong(), any(), any());
        verify(actionDispatcher, never()).dispatch(any(), anyLong(), any());
    }

    @Test
    @DisplayName("승인 대상 저장소 설정을 확인하지 못하면 실행하지 않고 409")
    void confirmRejectsUnresolvableApprovalTarget() {
        CommandLog commandLog = CommandLog.builder().user(testUser).rawCommand("배포해줘")
                .interpretedIntent(Intent.DEPLOY).intentArgs("{\"repository_id\":9}")
                .riskLevel(RiskLevel.HIGH).requiresConfirmation(true).session(testSession).build();
        commandLog.pinApprovedConfig("fp-approved");
        given(confirmations.claim(1L, 1L)).willReturn(commandLog);
        given(approvalTargets.isPinned(Intent.DEPLOY)).willReturn(true);
        given(approvalTargets.resolve(eq(Intent.DEPLOY), any(), eq(1L))).willReturn(Optional.empty());

        assertThatThrownBy(() -> nlpCommandService.confirmCommand(1L, new NlpConfirmRequest(1L, true)))
                .isInstanceOf(BusinessException.class);
        verify(actionDispatcher, never()).dispatch(any(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("취소 시 명령 미실행")
    void cancelCommandDoesNotExecute() {
        CommandLog commandLog = CommandLog.builder()
                .user(testUser)
                .rawCommand("배포해줘")
                .interpretedIntent(Intent.DEPLOY)
                .intentArgs("{\"repository_id\":1}")
                .riskLevel(RiskLevel.HIGH)
                .requiresConfirmation(true)
                .session(testSession)
                .build();

        given(confirmations.cancel(1L, 1L)).willReturn(commandLog);

        NlpCommandResponse response = nlpCommandService.confirmCommand(1L,
                new NlpConfirmRequest(1L, false));

        assertThat(response.message()).contains("취소");
        assertThat(response.result()).isNull();
        verify(actionDispatcher, never()).dispatch(any(), anyLong(), any());
    }

    @Test
    void foreignSessionIsRejectedBeforeGemini() {
        User stranger = User.builder().name("stranger").email("other@example.com").build();
        ReflectionTestUtils.setField(stranger, "id", 2L);
        given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
        given(sessionRepository.findBySessionTokenAndActiveTrue("other-session"))
                .willReturn(Optional.of(new ConversationSession(stranger)));

        assertThatThrownBy(() -> nlpCommandService.processCommand(1L,
                new NlpCommandRequest("deploy", "other-session")))
                .isInstanceOf(EntityNotFoundException.class);
        verifyNoInteractions(nlpLlm, actionDispatcher);
    }

    @Test
    void formattedFailureRecordsFailureWithoutSuccessMessage() {
        CommandLog commandLog = CommandLog.builder().user(testUser).rawCommand("deploy")
                .interpretedIntent(Intent.DEPLOY).intentArgs("{}")
                .riskLevel(RiskLevel.HIGH).requiresConfirmation(true).build();
        given(confirmations.claim(1L, 1L)).willReturn(commandLog);
        given(actionDispatcher.dispatch(any(), eq(1L), any(), any())).willReturn(
                FormattedResponseDto.of("error", "대상이 허용되지 않습니다", "오류", java.util.Map.of(), null));

        NlpCommandResponse response = nlpCommandService.confirmCommand(1L, new NlpConfirmRequest(1L, true));

        assertThat(response.message()).isEqualTo("대상이 허용되지 않습니다");
        verify(confirmations).fail(any(), eq("대상이 허용되지 않습니다"));
        verify(confirmations, never()).succeed(any(), any());
    }

    private ApprovalTargetResolver.Resolved resolved(String fingerprint) {
        return new ApprovalTargetResolver.Resolved(new ApprovalTarget(1L, "owner/app", "main", "HEAD",
                BuildStrategy.GITHUB_ACTIONS_GHCR, "ghcr.io/owner/app:sha-{commitHash}", 1, 1, 8080,
                List.of("SAMPLE_GREETING"), fingerprint.substring(0, 8)), fingerprint);
    }

    private LlmClient.Response llmResponse(String text) {
        return new LlmClient.Response(text, "test-model", new LlmClient.Usage(0, 0, null, null), 1);
    }
}
