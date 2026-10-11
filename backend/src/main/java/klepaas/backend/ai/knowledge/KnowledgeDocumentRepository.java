package klepaas.backend.ai.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    Optional<KnowledgeDocument> findByRepositoryIdAndSlug(Long repositoryId, String slug);

    List<KnowledgeDocument> findByRepositoryIdOrderBySlug(Long repositoryId);
}
