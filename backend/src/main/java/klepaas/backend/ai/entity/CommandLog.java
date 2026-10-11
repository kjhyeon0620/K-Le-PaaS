package klepaas.backend.ai.entity;

import jakarta.persistence.*;
import klepaas.backend.global.entity.BaseTimeEntity;
import klepaas.backend.user.entity.User;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "command_log")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CommandLog extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false)
    private String rawCommand;

    @Enumerated(EnumType.STRING)
    @Column(columnDefinition = "VARCHAR(50)")
    private Intent interpretedIntent;

    @Column(columnDefinition = "TEXT")
    private String intentArgs;

    @Enumerated(EnumType.STRING)
    private RiskLevel riskLevel;

    private boolean requiresConfirmation;

    private boolean confirmed;

    private boolean isExecuted;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private CommandStatus status;

    @Column(columnDefinition = "TEXT")
    private String executionResult;

    @Column(columnDefinition = "TEXT")
    private String aiResponse;

    private String errorMessage;

    // 승인 대상 배포 설정 지문 (#56). 승인 대상이 아니거나 확인하지 못한 명령은 null
    @Column(length = 64)
    private String approvedConfigFingerprint;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id")
    private ConversationSession session;

    @Builder
    public CommandLog(User user, String rawCommand, Intent interpretedIntent,
                      String intentArgs, RiskLevel riskLevel, boolean requiresConfirmation,
                      boolean isExecuted, String errorMessage, String aiResponse,
                      ConversationSession session) {
        this.user = user;
        this.rawCommand = rawCommand;
        this.interpretedIntent = interpretedIntent;
        this.intentArgs = intentArgs;
        this.riskLevel = riskLevel;
        this.requiresConfirmation = requiresConfirmation;
        this.isExecuted = isExecuted;
        this.status = requiresConfirmation ? CommandStatus.PENDING : CommandStatus.EXECUTING;
        this.errorMessage = errorMessage;
        this.aiResponse = aiResponse;
        this.session = session;
    }

    public void markExecuted(String executionResult) {
        this.isExecuted = true;
        this.executionResult = executionResult;
        this.status = CommandStatus.SUCCEEDED;
    }

    public void markFailed(String errorMessage) {
        this.isExecuted = false;
        this.errorMessage = errorMessage;
        this.status = CommandStatus.FAILED;
    }

    public void pinApprovedConfig(String fingerprint) {
        this.approvedConfigFingerprint = fingerprint;
    }

    public void confirm(boolean confirmed) {
        this.confirmed = confirmed;
    }
}
