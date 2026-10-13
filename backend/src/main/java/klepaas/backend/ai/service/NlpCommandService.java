package klepaas.backend.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import klepaas.backend.ai.llm.LlmClient;
import klepaas.backend.ai.dto.*;
import klepaas.backend.ai.entity.*;
import klepaas.backend.ai.exception.AiProcessingException;
import klepaas.backend.ai.repository.CommandLogRepository;
import klepaas.backend.ai.repository.ConversationSessionRepository;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.service.ResourceAccessService;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.infra.kubernetes.KubernetesErrorMessages;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Service
@Transactional(readOnly = true)
public class NlpCommandService {

    private final LlmClient nlpLlm;
    private final IntentParser intentParser;
    private final ActionDispatcher actionDispatcher;
    private final ApprovalTargetResolver approvalTargets;
    private final NameTargets nameTargets;
    private final CommandLogRepository commandLogRepository;
    private final CommandConfirmationService confirmations;
    private final ConversationSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final ResourceAccessService resourceAccessService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String systemPrompt;

    public NlpCommandService(
            @Qualifier("nlpLlm") LlmClient nlpLlm,
            IntentParser intentParser,
            ActionDispatcher actionDispatcher,
            ApprovalTargetResolver approvalTargets,
            NameTargets nameTargets,
            CommandLogRepository commandLogRepository,
            CommandConfirmationService confirmations,
            ConversationSessionRepository sessionRepository,
            UserRepository userRepository,
            ResourceAccessService resourceAccessService,
            @Value("classpath:prompts/system-prompt.txt") Resource systemPromptResource
    ) {
        this.nlpLlm = nlpLlm;
        this.intentParser = intentParser;
        this.actionDispatcher = actionDispatcher;
        this.approvalTargets = approvalTargets;
        this.nameTargets = nameTargets;
        this.commandLogRepository = commandLogRepository;
        this.confirmations = confirmations;
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.resourceAccessService = resourceAccessService;
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

        // 모델 호출 (#136). 실패하면 AI_001로 끝나고 명령 기록·실행은 없다
        LlmClient.Response llmResponse = nlpLlm.completeJson(NlpPrompt.build(systemPrompt, request.command()));
        String responseText = llmResponse.text();
        log.info("자연어 명령 해석: provider={}, model={}, latencyMs={}", nlpLlm.provider(), llmResponse.model(), llmResponse.latencyMs());

        // Intent 파싱
        ParsedIntent parsedIntent = intentParser.parse(responseText);

        // 리스크 분류
        RiskLevel riskLevel = actionDispatcher.classifyRisk(parsedIntent.intent());
        boolean requiresConfirmation = riskLevel != RiskLevel.LOW;

        // 앱 이름을 요청 사용자 저장소 하나로 해석해 대상을 인자에 고정한다 (#144). 승인 후 다시 찾지 않는다.
        // 대상 intent(재시작·스케일·롤백·롤백 목록)가 아니면 인자를 그대로 돌려준다
        NameTargets.Result named = nameTargets.resolve(parsedIntent.intent(), parsedIntent.args(), userId);
        if (named.question() != null) {
            return askBack(user, request, parsedIntent, riskLevel, responseText, session, named.question(), "대상 해석 실패");
        }
        parsedIntent = new ParsedIntent(parsedIntent.intent(), named.args(), parsedIntent.confidence(), parsedIntent.message());

        // 실행할 대상이 빠진 변경 명령은 승인 대기로 만들지 않고 되묻는다 (#135). 기록은 FAILED로 남긴다
        List<String> missingTargets = requiresConfirmation
                ? ChangeTargets.missing(parsedIntent.intent(), parsedIntent.args()) : List.of();
        if (!missingTargets.isEmpty()) {
            return askBack(user, request, parsedIntent, riskLevel, responseText, session,
                    ChangeTargets.question(parsedIntent.intent(), missingTargets), "대상 누락: " + String.join(", ", missingTargets));
        }

        // 인자 직렬화 (해석한 대상 포함)
        String intentArgsJson = serializeArgs(parsedIntent);

        // 승인할 배포 설정을 고정한다 (#56). 확인하지 못하면 지문 없이 저장되고 confirm에서 거절된다
        var approval = requiresConfirmation
                ? approvalTargets.resolve(parsedIntent.intent(), parsedIntent.args(), userId)
                : java.util.Optional.<ApprovalTargetResolver.Resolved>empty();
        // 재시작·스케일은 고정할 설정이 없고 대상만 보여 준다 (#144)
        ApprovalTarget approvalTarget = approval.map(ApprovalTargetResolver.Resolved::target).orElse(null);
        if (approvalTarget == null && requiresConfirmation) {
            approvalTarget = approvalTargets.describe(parsedIntent.intent(), parsedIntent.args(), userId).orElse(null);
        }

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
                approvalTarget
        );
    }

    /** 승인 대기를 만들지 않고 되묻는다. 기록은 FAILED로 남긴다 (#135, #144) */
    private NlpCommandResponse askBack(User user, NlpCommandRequest request, ParsedIntent parsedIntent, RiskLevel riskLevel,
                                       String responseText, ConversationSession session, String question, String reason) {
        CommandLog incomplete = CommandLog.builder()
                .user(user)
                .rawCommand(request.command())
                .interpretedIntent(parsedIntent.intent())
                .intentArgs(serializeArgs(parsedIntent))
                .riskLevel(riskLevel)
                .requiresConfirmation(false)
                .aiResponse(responseText)
                .session(session)
                .build();
        incomplete.markFailed(reason);
        commandLogRepository.save(incomplete);
        session.touch();
        sessionRepository.save(session);
        return new NlpCommandResponse(incomplete.getId(), parsedIntent.intent(), question, null, riskLevel,
                false, session.getSessionToken(), null);
    }

    /**
     * 에이전트의 복구 제안 (#102). 자연어 해석 없이 지정한 배포로 복구하는 승인 대기 명령(ROLLBACK)만 만든다.
     * 계획 지문을 고정하고 아무것도 실행하지 않는다. 실행은 사람이 confirm으로 승인할 때 지문을 다시 비교한 뒤에 한다.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public NlpCommandResponse proposeRecovery(Long userId, Long targetDeploymentId, String reason) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.USER_NOT_FOUND));
        // 권한 밖 배포는 404로 끝나고 명령을 만들지 않는다
        Deployment target = resourceAccessService.requireDeployment(targetDeploymentId, userId);
        var repo = target.getSourceRepository();
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("owner", repo.getOwner());
        args.put("repo", repo.getRepoName());
        args.put("commit_hash", target.getCommitHash());
        args.put("target_deployment_id", target.getId());
        var resolved = approvalTargets.resolve(Intent.ROLLBACK, args, userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RECOVERY_BLOCKED));
        if (resolved.fingerprint() == null) {
            throw new BusinessException(ErrorCode.RECOVERY_BLOCKED,
                    "복구할 수 없습니다: " + String.join("; ", resolved.target().blockedReasons()));
        }
        String commit = target.getCommitHash() == null ? "-" : target.getCommitHash().substring(0, Math.min(7, target.getCommitHash().length()));
        String raw = "복구 제안: 배포 #" + target.getId() + " (" + commit + ")"
                + (reason == null || reason.isBlank() ? "" : " — " + reason.strip());
        CommandLog commandLog = CommandLog.builder()
                .user(user)
                .rawCommand(raw)
                .interpretedIntent(Intent.ROLLBACK)
                .intentArgs(serializeArgs(new ParsedIntent(Intent.ROLLBACK, args, 1.0, "")))
                .riskLevel(actionDispatcher.classifyRisk(Intent.ROLLBACK))
                .requiresConfirmation(true)
                .build();
        commandLog.pinApprovedConfig(resolved.fingerprint());
        commandLogRepository.save(commandLog);
        return new NlpCommandResponse(commandLog.getId(), Intent.ROLLBACK,
                "복구 제안을 만들었습니다. 사람이 10분 안에 승인해야 실행됩니다.", null,
                commandLog.getRiskLevel(), true, null, resolved.target());
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
        confirmations.expireStale(userId);
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
