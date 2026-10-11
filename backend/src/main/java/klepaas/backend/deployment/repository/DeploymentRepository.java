package klepaas.backend.deployment.repository;

import klepaas.backend.deployment.entity.Deployment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.entity.FailureKind;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
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

    // 상세 화면의 비교 대상: 같은 저장소에서 이 배포보다 먼저 만들어진 마지막 성공 배포 (#95)
    Optional<Deployment> findFirstBySourceRepositoryIdAndStatusAndIdLessThanOrderByIdDesc(
            Long sourceRepositoryId, DeploymentStatus status, Long id);

    Optional<Deployment> findFirstBySourceRepositoryIdAndStatusInAndUpdatedAtAfterOrderByIdDesc(
            Long sourceRepositoryId, Collection<DeploymentStatus> statuses, LocalDateTime updatedAfter);

    @Query("SELECT s.user.id FROM Deployment d JOIN d.sourceRepository s WHERE d.id = :id")
    Optional<Long> findUserIdByDeploymentId(@Param("id") Long id);

    // 복구 후보 (#101): 같은 저장소의 이미지가 기록된 성공 배포, 최신순
    List<Deployment> findTop20BySourceRepositoryIdAndStatusAndImageUriIsNotNullOrderByIdDesc(
            Long sourceRepositoryId, DeploymentStatus status);

    // 재시작 대조(#57) 대상. 웹 서버가 요청을 받기 전에 읽는다
    @Query("SELECT d.id FROM Deployment d WHERE d.status IN :statuses ORDER BY d.id")
    List<Long> findIdsByStatusIn(@Param("statuses") Collection<DeploymentStatus> statuses);

    // 이전 상태가 그대로일 때만 대조 결과를 기록한다. bulk update라 updatedAt을 직접 넣는다
    @Modifying
    @Query("UPDATE Deployment d SET d.status = :next, d.failureKind = :kind, d.failReason = :reason, "
            + "d.imageDigest = :digest, d.finishedAt = :now, d.updatedAt = :now WHERE d.id = :id AND d.status = :expected")
    int finishIfStatus(@Param("id") Long id, @Param("expected") DeploymentStatus expected,
                       @Param("next") DeploymentStatus next, @Param("kind") FailureKind kind,
                       @Param("reason") String reason, @Param("digest") String digest,
                       @Param("now") LocalDateTime now);
}
