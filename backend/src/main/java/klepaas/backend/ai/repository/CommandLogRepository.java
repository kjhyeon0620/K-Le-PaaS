package klepaas.backend.ai.repository;

import klepaas.backend.ai.entity.CommandLog;
import klepaas.backend.ai.entity.CommandStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface CommandLogRepository extends JpaRepository<CommandLog, Long> {

    Page<CommandLog> findByUserId(Long userId, Pageable pageable);

    @Modifying
    @Query("update CommandLog c set c.status = :next, c.confirmed = :confirmed where c.id = :id and c.user.id = :userId and c.requiresConfirmation = true and c.status = :pending and c.createdAt > :cutoff")
    int consume(Long id, Long userId, CommandStatus pending, CommandStatus next, boolean confirmed, LocalDateTime cutoff);

    @Modifying
    @Query("update CommandLog c set c.status = :expired where c.id = :id and c.user.id = :userId and c.status = :pending and c.createdAt <= :cutoff")
    int expire(Long id, Long userId, CommandStatus pending, CommandStatus expired, LocalDateTime cutoff);

    @Modifying
    @Query("update CommandLog c set c.status = :next, c.isExecuted = :executed, c.executionResult = :result, c.errorMessage = :error where c.id = :id and c.status = :executing")
    int finish(Long id, CommandStatus executing, CommandStatus next, boolean executed, String result, String error);

    // 재시작 대조(#57) 대상. 웹 서버가 요청을 받기 전에 읽는다
    @Query("select c.id from CommandLog c where c.status = :status order by c.id")
    List<Long> findIdsByStatus(CommandStatus status);
}
