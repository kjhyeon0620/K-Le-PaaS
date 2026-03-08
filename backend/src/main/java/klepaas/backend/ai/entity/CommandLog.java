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

    @Column(columnDefinition = "TEXT")
    private String executionResult;

    @Column(columnDefinition = "TEXT")
    private String aiResponse;

    private String errorMessage;

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
        this.errorMessage = errorMessage;
        this.aiResponse = aiResponse;
        this.session = session;
    }

    public void markExecuted(String executionResult) {
        this.isExecuted = true;
        this.executionResult = executionResult;
    }

    public void markFailed(String errorMessage) {
        this.isExecuted = false;
        this.errorMessage = errorMessage;
    }

    public void confirm(boolean confirmed) {
        this.confirmed = confirmed;
    }
}
