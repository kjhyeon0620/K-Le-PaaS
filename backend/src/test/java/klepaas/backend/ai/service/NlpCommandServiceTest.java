package klepaas.backend.ai.service;

import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.ai.client.dto.GeminiResponse;
import klepaas.backend.ai.dto.NlpCommandRequest;
import klepaas.backend.ai.dto.NlpCommandResponse;
import klepaas.backend.ai.dto.NlpConfirmRequest;
import klepaas.backend.ai.dto.FormattedResponseDto;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NlpCommandServiceTest {

    @Mock private GeminiClient geminiClient;
    @Mock private IntentParser intentParser;
    @Mock private ActionDispatcher actionDispatcher;
    @Mock private CommandLogRepository commandLogRepository;
    @Mock private CommandConfirmationService confirmations;
    @Mock private ConversationSessionRepository sessionRepository;
    @Mock private UserRepository userRepository;

    private NlpCommandService nlpCommandService;
    private User testUser;
    private ConversationSession testSession;

    @BeforeEach
    void setUp() {
        var systemPromptResource = new ByteArrayResource("테스트 시스템 프롬프트".getBytes());
        nlpCommandService = new NlpCommandService(
                geminiClient, intentParser, actionDispatcher,
                commandLogRepository, confirmations, sessionRepository, userRepository,
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

    @Test
    @DisplayName("LOW 리스크 명령은 즉시 실행")
    void processLowRiskCommand() {
        given(userRepository.findById(1L)).willReturn(Optional.of(testUser));
        given(sessionRepository.save(any())).willReturn(testSession);
        given(geminiClient.generate(any())).willReturn(mockGeminiResponse("test"));
        given(intentParser.parse("test")).willReturn(
                new klepaas.backend.ai.dto.ParsedIntent(Intent.HELP, java.util.Map.of(), 1.0, "도움말"));
        given(actionDispatcher.classifyRisk(Intent.HELP)).willReturn(RiskLevel.LOW);
        given(actionDispatcher.dispatch(any(), eq(1L))).willReturn("도움말 결과");
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
        given(geminiClient.generate(any())).willReturn(mockGeminiResponse("test"));
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
        verify(actionDispatcher, never()).dispatch(any(), anyLong());
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

        given(confirmations.claim(1L, 1L)).willReturn(commandLog);
        given(actionDispatcher.dispatch(any(), eq(1L)))
                .willReturn("배포가 시작되었습니다");

        NlpCommandResponse response = nlpCommandService.confirmCommand(1L,
                new NlpConfirmRequest(1L, true));

        assertThat(response.result()).isEqualTo("배포가 시작되었습니다");
        verify(actionDispatcher).dispatch(any(), eq(1L));
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
        verify(actionDispatcher, never()).dispatch(any(), anyLong());
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
        verifyNoInteractions(geminiClient, actionDispatcher);
    }

    @Test
    void formattedFailureRecordsFailureWithoutSuccessMessage() {
        CommandLog commandLog = CommandLog.builder().user(testUser).rawCommand("deploy")
                .interpretedIntent(Intent.DEPLOY).intentArgs("{}")
                .riskLevel(RiskLevel.HIGH).requiresConfirmation(true).build();
        given(confirmations.claim(1L, 1L)).willReturn(commandLog);
        given(actionDispatcher.dispatch(any(), eq(1L))).willReturn(
                FormattedResponseDto.of("error", "대상이 허용되지 않습니다", "오류", java.util.Map.of(), null));

        NlpCommandResponse response = nlpCommandService.confirmCommand(1L, new NlpConfirmRequest(1L, true));

        assertThat(response.message()).isEqualTo("대상이 허용되지 않습니다");
        verify(confirmations).fail(any(), eq("대상이 허용되지 않습니다"));
        verify(confirmations, never()).succeed(any(), any());
    }

    private GeminiResponse mockGeminiResponse(String text) {
        return new GeminiResponse(List.of(
                new GeminiResponse.Candidate(
                        new GeminiResponse.Content(
                                List.of(new GeminiResponse.Part(text)),
                                "model"
                        )
                )
        ));
    }
}
