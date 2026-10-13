package klepaas.backend.ai.repository;

import klepaas.backend.ai.entity.ConversationSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConversationSessionRepository extends JpaRepository<ConversationSession, Long> {

    Optional<ConversationSession> findBySessionTokenAndActiveTrue(String sessionToken);
}
