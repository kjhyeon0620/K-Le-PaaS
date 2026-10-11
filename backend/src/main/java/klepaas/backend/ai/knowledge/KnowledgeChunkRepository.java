package klepaas.backend.ai.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    /** 그 앱의 조각과 플랫폼 runbook만 읽는다. 다른 앱의 조각은 DB에서 걸러진다 */
    @Query("select c from KnowledgeChunk c where c.repositoryId = :repositoryId or c.repositoryId is null")
    List<KnowledgeChunk> findSearchable(Long repositoryId);

    List<KnowledgeChunk> findBySourceType(KnowledgeChunk.Source sourceType);

    long countBySourceRef(String sourceRef);

    @Modifying
    @Query("delete from KnowledgeChunk c where c.sourceRef = :sourceRef")
    void deleteBySourceRef(String sourceRef);
}
