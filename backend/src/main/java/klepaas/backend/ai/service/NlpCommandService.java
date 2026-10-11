package klepaas.backend.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.ai.client.dto.GeminiRequest;
import klepaas.backend.ai.client.dto.GeminiResponse;
import klepaas.backend.ai.dto.*;
import klepaas.backend.ai.entity.*;
import klepaas.backend.ai.exception.AiProcessingException;
import klepaas.backend.ai.repository.CommandLogRepository;
import klepaas.backend.ai.repository.ConversationSessionRepository;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.infra.kubernetes.KubernetesErrorMessages;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@Transactional(readOnly = true)
public class NlpCommandService {

    private final GeminiClient geminiClient;
    private final IntentParser intentParser;
    private final ActionDispatcher actionDispatcher;
    private final ApprovalTargetResolver approvalTargets;
    private final CommandLogRepository commandLogRepository;
    private final CommandConfirmationService confirmations;
    private final ConversationSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String systemPrompt;

    public NlpCommandService(
            GeminiClient geminiClient,
            IntentParser intentParser,
            ActionDispatcher actionDispatcher,
            ApprovalTargetResolver approvalTargets,
            CommandLogRepository commandLogRepository,
            CommandConfirmationService confirmations,
            ConversationSessionRepository sessionRepository,
            UserRepository userRepository,
            @Value("classpath:prompts/system-prompt.txt") Resource systemPromptResource
    ) {
        this.geminiClient = geminiClient;
        this.intentParser = intentParser;
        this.actionDispatcher = actionDispatcher;
        this.approvalTargets = approvalTargets;
        this.commandLogRepository = commandLogRepository;
        this.confirmations = confirmations;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        try {
            this.systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("시스템 프롬프트 파일을 읽을 수 없습니다", e);
        }
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public NlpCommandResponse processCommand(Long userId, NlpCommandRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.USER_NOT_FOUND));

        // 세션 관리
        ConversationSession session = getOrCreateSession(user, request.sessionId());

        // Gemini API 호출
        GeminiRequest geminiRequest = GeminiRequest.of(systemPrompt, request.command());
        GeminiResponse geminiResponse = geminiClient.generate(geminiRequest);
        String responseText = geminiResponse.extractText();

        // Intent 파싱
        ParsedIntent parsedIntent = intentParser.parse(responseText);

        // 리스크 분류
        RiskLevel riskLevel = actionDispatcher.classifyRisk(parsedIntent.intent());
        boolean requiresConfirmation = riskLevel != RiskLevel.LOW;

        // 인자 직렬화
        String intentArgsJson = serializeArgs(parsedIntent);

        // 승인할 배포 설정을 고정한다 (#56). 확인하지 못하면 지문 없이 저장되고 confirm에서 거절된다
        var approval = requiresConfirmation
                ? approvalTargets.resolve(parsedIntent.intent(), parsedIntent.args(), userId)
                : java.util.Optional.<ApprovalTargetResolver.Resolved>empty();

        // CommandLog 저장
        CommandLog commandLog = CommandLog.builder()
                .user(user)
                .rawCommand(request.command())
                .interpretedIntent(parsedIntent.intent())
                .intentArgs(intentArgsJson)
                .riskLevel(riskLevel)
                .requiresConfirmation(requiresConfirmation)
                .aiResponse(responseText)
                .session(session)
                .build();
        approval.ifPresent(resolved -> commandLog.pinApprovedConfig(resolved.fingerprint()));
        commandLogRepository.save(commandLog);

        // LOW 리스크는 즉시 실행
        Object result = null;
        if (!requiresConfirmation) {
            try {
                result = actionDispatcher.dispatch(parsedIntent, userId, commandLog.getId());
                if (result instanceof FormattedResponseDto formatted && "error".equals(formatted.type())) {
                    commandLog.markFailed(formatted.message());
                } else {
                    commandLog.markExecuted(toJsonString(result));
                }
            } catch (Exception e) {
                log.error("명령 실행 실패: intent={}", parsedIntent.intent(), e);
                commandLog.markFailed(KubernetesErrorMessages.userMessage(e));
                result = null;
                commandLogRepository.save(commandLog);
                if (e instanceof BusinessException businessException) throw businessException;
            }
            commandLogRepository.save(commandLog);
        }

        session.touch();
        sessionRepository.save(session);

        return new NlpCommandResponse(
                commandLog.getId(),
                parsedIntent.intent(),
                commandLog.getStatus() == CommandStatus.FAILED ? commandLog.getErrorMessage() : parsedIntent.message(),
                result,
                riskLevel,
                requiresConfirmation,
                session.getSessionToken(),
                approval.map(ApprovalTargetResolver.Resolved::target).orElse(null)
        );
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public NlpCommandResponse confirmCommand(Long userId, NlpConfirmRequest request) {
        CommandLog commandLog = request.confirmed()
                ? confirmations.claim(request.commandLogId(), userId)
                : confirmations.cancel(request.commandLogId(), userId);
        if (commandLog == null) {
            throw new BusinessException(ErrorCode.COMMAND_CONFIRMATION_CONFLICT);
        }

        if (!request.confirmed()) {
            return new NlpCommandResponse(
                    commandLog.getId(),
                    commandLog.getInterpretedIntent(),
                    "명령이 취소되었습니다.",
                    null,
                    commandLog.getRiskLevel(),
                    false,
                    commandLog.getSession() != null ? commandLog.getSession().getSessionToken() : null
            );
        }

        // 저장된 Intent 정보로 실행
        Object result;
        String message = "명령이 실행되었습니다.";
        ApprovalTarget approvalTarget = null;
        try {
            ParsedIntent parsedIntent = deserializeParsedIntent(commandLog);
            String approvedFingerprint = null;
            // 승인 후 설정이 바뀌었거나 승인 대상을 확인할 수 없으면 실행하지 않는다 (#56)
            if (approvalTargets.isPinned(parsedIntent.intent())) {
                var current = approvalTargets.resolve(parsedIntent.intent(), parsedIntent.args(), userId).orElse(null);
                if (current == null || current.fingerprint() == null
                        || !current.fingerprint().equals(commandLog.getApprovedConfigFingerprint())) {
                    throw new BusinessException(ErrorCode.APPROVAL_TARGET_CHANGED);
                }
                approvalTarget = current.target();
                approvedFingerprint = current.fingerprint();
            }
            result = actionDispatcher.dispatch(parsedIntent, userId, commandLog.getId(), approvedFingerprint);
            if (result instanceof FormattedResponseDto formatted && "error".equals(formatted.type())) {
                confirmations.fail(commandLog.getId(), formatted.message());
                message = formatted.message();
            } else {
                confirmations.succeed(commandLog.getId(), toJsonString(result));
            }
        } catch (Exception e) {
            log.error("확인 명령 실행 실패: intent={}", commandLog.getInterpretedIntent(), e);
            String reason = KubernetesErrorMessages.userMessage(e);
            confirmations.fail(commandLog.getId(), reason);
            if (e instanceof BusinessException b && b.getErrorCode() == ErrorCode.APPROVAL_TARGET_CHANGED) {
                throw b;
            }
            message = "명령 실행에 실패했습니다: " + reason;
            result = null;
        }

        return new NlpCommandResponse(
                commandLog.getId(),
                commandLog.getInterpretedIntent(),
                message,
                result,
                commandLog.getRiskLevel(),
                false,
                commandLog.getSession() != null ? commandLog.getSession().getSessionToken() : null,
                approvalTarget
        );
    }

    public Page<CommandLogResponse> getHistory(Long userId, Pageable pageable) {
        return commandLogRepository.findByUserId(userId, pageable)
                .map(CommandLogResponse::from);
    }

    private ConversationSession getOrCreateSession(User user, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionRepository.findBySessionTokenAndActiveTrue(sessionId)
                    .filter(session -> user.getId().equals(session.getUser().getId()))
                    .orElseThrow(() -> new EntityNotFoundException(ErrorCode.SESSION_NOT_FOUND));
        }
        return sessionRepository.save(new ConversationSession(user));
    }

    private String serializeArgs(ParsedIntent parsedIntent) {
        try {
            return objectMapper.writeValueAsString(parsedIntent.args());
        } catch (JsonProcessingException e) {
            log.warn("Intent 인자 직렬화 실패", e);
            return "{}";
        }
    }

    private String toJsonString(Object obj) {
        if (obj == null) return null;
        if (obj instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("결과 직렬화 실패", e);
            return obj.toString();
        }
    }

    private ParsedIntent deserializeParsedIntent(CommandLog commandLog) {
        try {
            var args = objectMapper.readValue(
                    commandLog.getIntentArgs(),
                    new com.fasterxml.jackson.core.type.TypeReference<java.util.Map<String, Object>>() {}
            );
            return new ParsedIntent(commandLog.getInterpretedIntent(), args, 1.0, "");
        } catch (JsonProcessingException e) {
            throw new AiProcessingException(ErrorCode.AI_PARSE_ERROR,
                    "저장된 Intent 인자 복원 실패: " + e.getMessage());
        }
    }
}
