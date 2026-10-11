package klepaas.backend.ai.diagnosis;

import jakarta.persistence.EntityManager;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.global.config.JpaConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/** V8 진단 이력: 배포별 최근 행을 읽고, 호출 생략 행은 사용량이 null이다 (#117). */
@DataJpaTest
@Import(JpaConfig.class)
class DeploymentDiagnosisRepositoryTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DeploymentDiagnosisRepository repository;

    @Test
    void latestRowPerDeploymentWins() {
        var deployment = Deployment.builder().branchName("main").commitHash("abcdef1").build();
        var other = Deployment.builder().branchName("main").commitHash("abcdef2").build();
        entityManager.persist(deployment);
        entityManager.persist(other);
        repository.save(new DeploymentDiagnosis(deployment.getId(), "luna", "diagnosis-v1", "{\"a\":1}", 100L, 0L, 50L, 10L, 5L));
        repository.save(new DeploymentDiagnosis(deployment.getId(), "rule", "no-evidence-v1", "{\"a\":2}", null, null, null, null, null));
        repository.save(new DeploymentDiagnosis(other.getId(), "luna", "diagnosis-v1", "{\"a\":3}", 1L, 0L, 1L, 0L, 1L));
        entityManager.flush();
        entityManager.clear();

        var latest = repository.findFirstByDeploymentIdOrderByIdDesc(deployment.getId()).orElseThrow();

        assertThat(latest.getResult()).isEqualTo("{\"a\":2}");
        assertThat(latest.getInputTokens()).isNull();
        assertThat(latest.getCreatedAt()).isNotNull();
        assertThat(repository.findFirstByDeploymentIdOrderByIdDesc(-1L)).isEmpty();
    }
}
