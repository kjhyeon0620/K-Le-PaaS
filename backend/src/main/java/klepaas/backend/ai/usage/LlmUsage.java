package klepaas.backend.ai.usage;

import jakarta.persistence.*;
import klepaas.backend.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/** LLM 호출 한 번의 사용량 (#176). 프롬프트·응답은 남기지 않는다. 비용은 가격표로 계산한 값이고 가격이 없으면 null이다 */
@Getter
@Entity
@Table(name = "llm_usages")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LlmUsage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 20)
    private String feature;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(nullable = false, length = 100)
    private String model;

    /** success·error */
    @Column(nullable = false, length = 10)
    private String outcome;

    private Long inputTokens;
    private Long cachedInputTokens;
    private Long outputTokens;
    private Long reasoningTokens;

    @Column(precision = 12, scale = 6)
    private BigDecimal costUsd;

    private Long latencyMs;
    private Long deploymentId;

    public LlmUsage(Long userId, String feature, String provider, String model, String outcome, Long inputTokens,
                    Long cachedInputTokens, Long outputTokens, Long reasoningTokens, BigDecimal costUsd, Long latencyMs,
                    Long deploymentId) {
        this.userId = userId;
        this.feature = feature;
        this.provider = provider;
        this.model = model;
        this.outcome = outcome;
        this.inputTokens = inputTokens;
        this.cachedInputTokens = cachedInputTokens;
        this.outputTokens = outputTokens;
        this.reasoningTokens = reasoningTokens;
        this.costUsd = costUsd;
        this.latencyMs = latencyMs;
        this.deploymentId = deploymentId;
    }
}
