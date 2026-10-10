package klepaas.backend.deployment.entity;

import jakarta.persistence.EntityManager;
import klepaas.backend.global.config.JpaConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(JpaConfig.class)
class DeploymentEvidenceSchemaTest {
    @Autowired
    private EntityManager entityManager;

    @Test
    void appliedEvidenceRoundTripsWhileLegacyEvidenceStaysNull() {
        var applied = Deployment.builder().branchName("main").commitHash("abcdef1").build();
        applied.recordAppliedResource("deployment-uid", 42L);
        var legacy = Deployment.builder().branchName("main").commitHash("abcdef2").build();
        entityManager.persist(applied);
        entityManager.persist(legacy);

        entityManager.flush();
        entityManager.clear();

        var stored = entityManager.find(Deployment.class, applied.getId());
        assertThat(stored.getAppliedResourceUid()).isEqualTo("deployment-uid");
        assertThat(stored.getAppliedGeneration()).isEqualTo(42L);
        var old = entityManager.find(Deployment.class, legacy.getId());
        assertThat(old.getAppliedResourceUid()).isNull();
        assertThat(old.getAppliedGeneration()).isNull();
    }

    @Test
    void readsUnknownWithoutAProductionTransitionThatWritesIt() {
        var deployment = Deployment.builder().branchName("main").commitHash("abcdef1").build();
        entityManager.persist(deployment);
        entityManager.flush();

        entityManager.createNativeQuery("update deployments set status = 'UNKNOWN' where id = :id")
                .setParameter("id", deployment.getId()).executeUpdate();
        entityManager.clear();

        assertThat(entityManager.find(Deployment.class, deployment.getId()).getStatus())
                .isEqualTo(DeploymentStatus.UNKNOWN);
    }
}
