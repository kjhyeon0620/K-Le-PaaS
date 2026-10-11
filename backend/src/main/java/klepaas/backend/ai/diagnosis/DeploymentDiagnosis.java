package klepaas.backend.ai.diagnosis;

import jakarta.persistence.*;
import klepaas.backend.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 진단 결과 이력 (#117). 응답 JSON과 모델 사용량을 남긴다. 호출을 생략한 결과는 사용량이 null이다. */
@Getter
@Entity
@Table(name = "deployment_diagnoses")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeploymentDiagnosis extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long deploymentId;

    @Column(nullable = false, length = 100)
    private String requestedModel;

    @Column(nullable = false, length = 50)
    private String promptVersion;

    @Column(nullable = false, columnDefinition = "text")
    private String result;

    private Long inputTokens;
    private Long cachedInputTokens;
    private Long outputTokens;
    private Long reasoningTokens;
    private Long latencyMs;

    public DeploymentDiagnosis(Long deploymentId, String requestedModel, String promptVersion, String result,
                               Long inputTokens, Long cachedInputTokens, Long outputTokens, Long reasoningTokens,
                               Long latencyMs) {
        this.deploymentId = deploymentId;
        this.requestedModel = requestedModel;
        this.promptVersion = promptVersion;
        this.result = result;
        this.inputTokens = inputTokens;
        this.cachedInputTokens = cachedInputTokens;
        this.outputTokens = outputTokens;
        this.reasoningTokens = reasoningTokens;
        this.latencyMs = latencyMs;
    }
}
