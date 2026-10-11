package klepaas.backend.ai.knowledge;

import klepaas.backend.ai.exception.AiProcessingException;
import klepaas.backend.ai.knowledge.KnowledgeDtos.IncidentRequest;
import klepaas.backend.ai.knowledge.KnowledgeDtos.PutDocumentRequest;
import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.repository.DeploymentRepository;
import klepaas.backend.deployment.repository.SourceRepositoryRepository;
import klepaas.backend.deployment.service.ResourceAccessService;
import klepaas.backend.global.config.JpaConfig;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.global.exception.InvalidRequestException;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** 지식 색인·검색 (#104): 실제 PostgreSQL(V9), 임베딩은 단어 기반 가짜 벡터. */
@DataJpaTest
@Import({KnowledgeService.class, ResourceAccessService.class, JpaConfig.class})
class KnowledgeServiceTest {

    @Autowired private KnowledgeService knowledge;
    @Autowired private KnowledgeChunkRepository chunks;
    @Autowired private KnowledgeDocumentRepository documents;
    @Autowired private UserRepository users;
    @Autowired private SourceRepositoryRepository repositories;
    @Autowired private DeploymentRepository deployments;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private jakarta.persistence.EntityManager entityManager;
    @MockitoBean private Embeddings embeddings;

    private User owner;
    private SourceRepository app;
    private SourceRepository otherApp;

    @BeforeEach
    void setUp() {
        when(embeddings.available()).thenReturn(true);
        when(embeddings.model()).thenReturn("fake-embedding");
        // 단어가 있으면 그 축이 1인 벡터. 같은 단어를 가진 글끼리 가깝다
        when(embeddings.embed(anyList())).thenAnswer(inv -> ((List<String>) inv.getArgument(0)).stream().map(text -> new float[]{
                text.contains("E_BOOT_17") ? 1 : 0, text.contains("백업") ? 1 : 0, text.contains("ImagePull") ? 1 : 0, 0.05f}).toList());
        owner = users.save(User.builder().name("owner").email("knowledge-owner@example.test").role(Role.USER).build());
        User other = users.save(User.builder().name("other").email("knowledge-other@example.test").role(Role.USER).build());
        app = repositories.save(SourceRepository.builder().user(owner).owner("kn").repoName("orders")
                .gitUrl("https://example.test/kn/orders").cloudVendor(CloudVendor.ON_PREMISE).build());
        otherApp = repositories.save(SourceRepository.builder().user(other).owner("kn").repoName("billing")
                .gitUrl("https://example.test/kn/billing").cloudVendor(CloudVendor.ON_PREMISE).build());
    }

    @Test
    void documentsAreRedactedSplitIndexedReplacedAndDeleted() {
        String content = "# 기동 오류\n\nE_BOOT_17은 ADMIN_PASSWORD가 비었을 때 난다. 예: password=hunter2\n\n# 백업\n\n매일 백업한다.\n\n"
                + "x".repeat(KnowledgeText.CHUNK_CHARS + 50);
        var created = knowledge.putDocument(app.getId(), "runbook", new PutDocumentRequest("orders 운영", content), owner.getId());

        assertThat(created.revision()).isEqualTo(1);
        List<KnowledgeChunk> rows = chunks.findAll();
        assertThat(rows).hasSize((int) created.chunks()).hasSizeGreaterThanOrEqualTo(3);
        assertThat(rows).allSatisfy(c -> {
            assertThat(c.getRepositoryId()).isEqualTo(app.getId());
            assertThat(c.getText()).doesNotContain("hunter2").startsWith("orders 운영");
            assertThat(KnowledgeText.fromBytes(c.getEmbedding())).hasSize(4);
        });
        assertThat(documents.findAll()).singleElement().satisfies(d -> assertThat(d.getContent()).contains("password=[REDACTED]"));

        var updated = knowledge.putDocument(app.getId(), "runbook", new PutDocumentRequest("orders 운영", "# 백업\n\n주 1회 백업"), owner.getId());
        assertThat(updated.revision()).isEqualTo(2);
        assertThat(chunks.findAll()).singleElement().satisfies(c -> {
            assertThat(c.getText()).contains("주 1회");
            assertThat(c.getSourceVersion()).isEqualTo("rev 2");
        });

        knowledge.deleteDocument(app.getId(), "runbook", owner.getId());
        assertThat(chunks.findAll()).isEmpty();
        assertThat(documents.findAll()).isEmpty();
        assertThatThrownBy(() -> knowledge.deleteDocument(app.getId(), "runbook", owner.getId())).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void searchOnlyReadsOwnAppAndPlatformAndSkipsTheDiagnosedDeploymentsIncident() {
        knowledge.putDocument(app.getId(), "runbook", new PutDocumentRequest("orders 운영", "E_BOOT_17 → ADMIN_PASSWORD 확인"), owner.getId());
        Long other = otherApp.getUser().getId();
        knowledge.putDocument(otherApp.getId(), "runbook", new PutDocumentRequest("billing 운영", "E_BOOT_17 billing 전용"), other);
        knowledge.indexPlatform("image-pull.md", "이미지", "# 이미지\n\nImagePullBackOff 대응");
        long past = failedDeployment(app, "aaaaaaa1");
        long current = failedDeployment(app, "bbbbbbb2");
        knowledge.confirmIncident(past, new IncidentRequest("ENV_CONFIG", "required_env_missing", "ADMIN_PASSWORD 복구 E_BOOT_17"), owner.getId());
        knowledge.confirmIncident(current, new IncidentRequest("ENV_CONFIG", "required_env_missing", "E_BOOT_17"), owner.getId());

        var found = knowledge.search(app.getId(), "kn/orders", current,
                "{\"detail\":{\"deployment\":{\"fail_reason\":\"E_BOOT_17\"}},\"logs\":{}}");

        assertThat(found).extracting(KnowledgeDtos.KnowledgeItem::ref)
                .contains("incident:" + past).doesNotContain("incident:" + current);
        assertThat(found).noneMatch(f -> f.text().contains("billing"));
        assertThat(found).allMatch(f -> f.similarity() >= 0.45);
        assertThat(found.get(0).repository()).isEqualTo("kn/orders");
        // 질의와 관계없는 플랫폼 runbook은 기준값 아래라 빠진다
        assertThat(found).noneMatch(f -> f.source().equals("PLATFORM"));
    }

    @Test
    void noCandidatesMeansNoEmbeddingCall() {
        assertThat(knowledge.search(app.getId(), "kn/orders", 1L, "{}")).isEmpty();
        verify(embeddings, never()).embed(anyList());
    }

    @Test
    void incidentsNeedFailedDeploymentKnownLabelsAndOwnership() {
        long failed = failedDeployment(app, "ccccccc3");
        assertThatThrownBy(() -> knowledge.confirmIncident(failed, new IncidentRequest("ENV_CONFIG", "missing_password", null), owner.getId()))
                .isInstanceOfSatisfying(InvalidRequestException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST));
        Deployment ok = deployments.saveAndFlush(Deployment.builder().sourceRepository(app).branchName("main").commitHash("ddddddd4").build());
        jdbc.update("update deployments set status = 'SUCCESS' where id = ?", ok.getId());
        entityManager.clear();
        assertThatThrownBy(() -> knowledge.confirmIncident(ok.getId(), new IncidentRequest("ENV_CONFIG", "required_env_missing", null), owner.getId()))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> knowledge.confirmIncident(failed, new IncidentRequest("ENV_CONFIG", "required_env_missing", null),
                otherApp.getUser().getId())).isInstanceOf(EntityNotFoundException.class);

        var incident = knowledge.confirmIncident(failed, new IncidentRequest("ENV_CONFIG", "required_env_missing", "token=abc 다시 넣음"), owner.getId());
        assertThat(incident.resolution()).contains("token=[REDACTED]");
        assertThat(chunks.findAll()).singleElement().satisfies(c -> {
            assertThat(c.getSourceType()).isEqualTo(KnowledgeChunk.Source.INCIDENT);
            assertThat(c.getDeploymentId()).isEqualTo(failed);
            assertThat(c.getText()).contains("확정 원인: ENV_CONFIG / required_env_missing").contains("commit ccccccc3");
        });
        knowledge.deleteIncident(failed, owner.getId());
        assertThat(chunks.findAll()).isEmpty();
    }

    @Test
    void embeddingFailureSavesNothing() {
        when(embeddings.embed(anyList())).thenThrow(new AiProcessingException(ErrorCode.AI_API_ERROR, "down"));
        assertThatThrownBy(() -> knowledge.putDocument(app.getId(), "runbook", new PutDocumentRequest("t", "본문"), owner.getId()))
                .isInstanceOf(AiProcessingException.class);
        assertThat(documents.findAll()).isEmpty();
        assertThat(chunks.findAll()).isEmpty();
    }

    @Test
    void platformRunbooksReindexOnlyWhenChangedAndRemovedFilesDisappear() {
        assertThat(knowledge.indexPlatform("a.md", "A", "# A\n\n내용")).isTrue();
        assertThat(knowledge.indexPlatform("a.md", "A", "# A\n\n내용")).isFalse();
        assertThat(knowledge.indexPlatform("a.md", "A", "# A\n\n바뀐 내용")).isTrue();
        knowledge.indexPlatform("b.md", "B", "# B\n\n내용");
        knowledge.removePlatformExcept(List.of("b.md"));
        assertThat(chunks.findAll()).extracting(KnowledgeChunk::getSourceRef).containsOnly("platform:b.md");
        assertThat(chunks.findAll()).allMatch(c -> c.getRepositoryId() == null);
    }

    private long failedDeployment(SourceRepository repo, String commit) {
        Deployment d = deployments.saveAndFlush(Deployment.builder().sourceRepository(repo).branchName("main").commitHash(commit).build());
        jdbc.update("update deployments set status = 'FAILED', failure_kind = 'CRASH_LOOP', fail_reason = 'preflight E_BOOT_17' where id = ?", d.getId());
        entityManager.clear();
        return d.getId();
    }
}
