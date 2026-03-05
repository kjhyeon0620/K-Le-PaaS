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

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@Transactional(readOnly = true)
public class NlpCommandService {

    private final GeminiClient geminiClient;
    private final IntentParser intentParser;
    private final ActionDispatcher actionDispatcher;
    private final CommandLogRepository commandLogRepository;
    private final ConversationSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String systemPrompt;

    public NlpCommandService(
            GeminiClient geminiClient,
            IntentParser intentParser,
            ActionDispatcher actionDispatcher,
            CommandLogRepository commandLogRepository,
            ConversationSessionRepository sessionRepository,
            UserRepository userRepository,
            @Value("classpath:prompts/system-prompt.txt") Resource systemPromptResource
    ) {
        this.geminiClient = geminiClient;
        this.intentParser = intentParser;
        this.actionDispatcher = actionDispatcher;
        this.commandLogRepository = commandLogRepository;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        try {
            this.systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("시스템 프롬프트 파일을 읽을 수 없습니다", e);
        }
    }

    @Transactional
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
        commandLogRepository.save(commandLog);

        // LOW 리스크는 즉시 실행
        Object result = null;
        if (!requiresConfirmation) {
            try {
                result = actionDispatcher.dispatch(parsedIntent, userId);
                commandLog.markExecuted(toJsonString(result));
            } catch (Exception e) {
                log.error("명령 실행 실패: intent={}", parsedIntent.intent(), e);
                commandLog.markFailed(e.getMessage());
                result = null;
            }
        }

        session.touch();

        return new NlpCommandResponse(
                commandLog.getId(),
                parsedIntent.intent(),
                parsedIntent.message(),
                result,
                riskLevel,
                requiresConfirmation,
                session.getSessionToken()
        );
    }

    @Transactional
    public NlpCommandResponse confirmCommand(Long userId, NlpConfirmRequest request) {
        CommandLog commandLog = commandLogRepository.findById(request.commandLogId())
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.COMMAND_LOG_NOT_FOUND));

        commandLog.confirm(request.confirmed());

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
        ParsedIntent parsedIntent = deserializeParsedIntent(commandLog);
        Object result;
        try {
            result = actionDispatcher.dispatch(parsedIntent, userId);
            commandLog.markExecuted(toJsonString(result));
        } catch (Exception e) {
            log.error("확인 명령 실행 실패: intent={}", commandLog.getInterpretedIntent(), e);
            commandLog.markFailed(e.getMessage());
            result = null;
        }

        return new NlpCommandResponse(
                commandLog.getId(),
                commandLog.getInterpretedIntent(),
                "명령이 실행되었습니다.",
                result,
                commandLog.getRiskLevel(),
                false,
                commandLog.getSession() != null ? commandLog.getSession().getSessionToken() : null
        );
    }

    public Page<CommandLogResponse> getHistory(Long userId, Pageable pageable) {
        return commandLogRepository.findByUserId(userId, pageable)
                .map(CommandLogResponse::from);
    }

    private ConversationSession getOrCreateSession(User user, String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionRepository.findBySessionTokenAndActiveTrue(sessionId)
                    .orElseGet(() -> sessionRepository.save(new ConversationSession(user)));
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
