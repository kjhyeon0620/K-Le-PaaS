package klepaas.backend.deployment.repository;

import klepaas.backend.deployment.entity.Deployment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import klepaas.backend.deployment.entity.DeploymentStatus;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

public interface DeploymentRepository extends JpaRepository<Deployment, Long> {

    // sourceRepository는 파이프라인 전 단계에서 항상 필요하므로 항상 fetch join
    @EntityGraph(attributePaths = {"sourceRepository"})
    @Override
    Optional<Deployment> findById(Long id);

    // 배포 이력은 양이 많으므로 페이징 처리
    @EntityGraph(attributePaths = {"sourceRepository"})
    Page<Deployment> findBySourceRepositoryId(Long sourceRepositoryId, Pageable pageable);

    @EntityGraph(attributePaths = {"sourceRepository"})
    Page<Deployment> findBySourceRepositoryUserId(Long userId, Pageable pageable);

    Optional<Deployment> findByGithubDeliveryId(String githubDeliveryId);

    Optional<Deployment> findFirstBySourceRepositoryIdAndStatusInAndUpdatedAtAfterOrderByIdDesc(
            Long sourceRepositoryId, Collection<DeploymentStatus> statuses, LocalDateTime updatedAfter);

    @Query("SELECT s.user.id FROM Deployment d JOIN d.sourceRepository s WHERE d.id = :id")
    Optional<Long> findUserIdByDeploymentId(@Param("id") Long id);
}
