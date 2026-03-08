package klepaas.backend.deployment.entity;

import jakarta.persistence.*;
import klepaas.backend.global.entity.BaseTimeEntity;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "scaling_history")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ScalingHistory extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deployment_id", nullable = false)
    private Deployment deployment;

    @Column(nullable = false)
    private int previousReplicas;

    @Column(nullable = false)
    private int newReplicas;

    @Column(nullable = false, length = 20)
    private String triggeredBy;

    @Builder
    public ScalingHistory(Deployment deployment, int previousReplicas, int newReplicas, String triggeredBy) {
        this.deployment = deployment;
        this.previousReplicas = previousReplicas;
        this.newReplicas = newReplicas;
        this.triggeredBy = triggeredBy;
    }
}
