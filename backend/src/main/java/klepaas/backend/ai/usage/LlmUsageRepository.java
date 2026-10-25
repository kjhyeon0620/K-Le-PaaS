package klepaas.backend.ai.usage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface LlmUsageRepository extends JpaRepository<LlmUsage, Long> {

    @Query("select coalesce(sum(u.costUsd), 0) from LlmUsage u where u.userId = :userId and u.createdAt >= :since")
    BigDecimal sumCostSince(@Param("userId") Long userId, @Param("since") LocalDateTime since);

    List<LlmUsage> findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(Long userId, LocalDateTime since);
}
