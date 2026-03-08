package klepaas.backend.deployment.repository;

import klepaas.backend.deployment.entity.ScalingHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ScalingHistoryRepository extends JpaRepository<ScalingHistory, Long> {

    @EntityGraph(attributePaths = {"deployment"})
    Page<ScalingHistory> findByDeploymentSourceRepositoryIdOrderByCreatedAtDesc(Long repoId, Pageable pageable);
}
