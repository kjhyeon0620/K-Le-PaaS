package klepaas.backend.ai.diagnosis;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeploymentDiagnosisRepository extends JpaRepository<DeploymentDiagnosis, Long> {

    Optional<DeploymentDiagnosis> findFirstByDeploymentIdOrderByIdDesc(Long deploymentId);
}
