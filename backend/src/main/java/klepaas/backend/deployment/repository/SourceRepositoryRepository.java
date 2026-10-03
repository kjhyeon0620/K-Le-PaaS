package klepaas.backend.deployment.repository;

import jakarta.persistence.LockModeType;
import klepaas.backend.deployment.entity.SourceRepository;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SourceRepositoryRepository extends JpaRepository<SourceRepository, Long> {

    // N+1 문제 해결: User 정보를 함께 가져와야 할 때 fetch join 대신 @EntityGraph 사용
    // attributePaths에 명시된 연관관계는 Eager로 가져오고, 나머진 Lazy로 유지한다.
    @EntityGraph(attributePaths = {"user"})
    List<SourceRepository> findAllByUserId(Long userId);

    Optional<SourceRepository> findByOwnerAndRepoName(String owner, String repoName);

    // 같은 저장소의 배포 생성을 직렬화하는 행 잠금 (트랜잭션 종료까지 유지)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM SourceRepository r WHERE r.id = :id")
    Optional<SourceRepository> findByIdForUpdate(@Param("id") Long id);
}
