package klepaas.backend.ai.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    Optional<Incident> findByDeploymentId(Long deploymentId);

    List<Incident> findByRepositoryIdOrderByIdDesc(Long repositoryId);
}
