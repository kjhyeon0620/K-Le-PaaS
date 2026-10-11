package klepaas.backend.ai.knowledge;

import klepaas.backend.ai.diagnosis.DiagnosisLabels;
import klepaas.backend.ai.diagnosis.DiagnosisPrompt;
import klepaas.backend.ai.knowledge.KnowledgeChunk.Source;
import klepaas.backend.ai.knowledge.KnowledgeDtos.*;
import klepaas.backend.deployment.entity.Deployment;
import klepaas.backend.deployment.entity.DeploymentStatus;
import klepaas.backend.deployment.entity.SourceRepository;
import klepaas.backend.deployment.service.ResourceAccessService;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.global.exception.InvalidRequestException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 앱 문서·확정 사건·플랫폼 runbook 색인과 검색 (#104).
 * 저장 전에 비밀값 패턴을 가리고, 임베딩을 먼저 만든 뒤 저장한다(임베딩 실패면 아무것도 바꾸지 않는다).
 * 검색은 그 앱과 플랫폼 조각만 DB에서 읽어 코사인 유사도로 고른다.
 */
@Slf4j
@Service
public class KnowledgeService {

    public static final int MAX_DOCUMENT_CHARS = 50_000;
    private static final Pattern SLUG = Pattern.compile("[a-z0-9-]{1,64}");

    private final ResourceAccessService resourceAccessService;
    private final KnowledgeDocumentRepository documents;
    private final IncidentRepository incidents;
    private final KnowledgeChunkRepository chunks;
    private final Embeddings embeddings;
    private final int topK;
    private final double minSimilarity;
    // #123 독립 평가에서 개선이 없어 기본으로 끈다. 꺼져 있으면 진단 검색·임베딩을 하지 않는다. 문서·사건 저장은 그대로다
    private final boolean enabled;

    public KnowledgeService(ResourceAccessService resourceAccessService, KnowledgeDocumentRepository documents,
                            IncidentRepository incidents, KnowledgeChunkRepository chunks, Embeddings embeddings,
                            @Value("${diagnosis.rag.top-k:4}") int topK,
                            @Value("${diagnosis.rag.min-similarity:0.45}") double minSimilarity,
                            @Value("${diagnosis.rag.enabled:false}") boolean enabled) {
        this.resourceAccessService = resourceAccessService;
        this.documents = documents;
        this.incidents = incidents;
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.topK = topK;
        this.minSimilarity = minSimilarity;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    @Transactional(readOnly = true)
    public KnowledgeListResponse list(Long repositoryId, Long userId) {
        resourceAccessService.requireRepository(repositoryId, userId);
        return new KnowledgeListResponse(repositoryId,
                documents.findByRepositoryIdOrderBySlug(repositoryId).stream().map(d -> new DocumentSummary(d.getSlug(),
                        d.getTitle(), d.getRevision(), chunks.countBySourceRef(documentRef(d.getId())), d.getUpdatedAt())).toList(),
                incidents.findByRepositoryIdOrderByIdDesc(repositoryId).stream().map(i -> new IncidentSummary(i.getDeploymentId(),
                        i.getCategory(), i.getRootCause(), i.getResolution(), chunks.countBySourceRef(incidentRef(i.getDeploymentId())),
                        i.getUpdatedAt())).toList());
    }

    @Transactional
    public DocumentSummary putDocument(Long repositoryId, String slug, PutDocumentRequest request, Long userId) {
        SourceRepository repo = resourceAccessService.requireRepository(repositoryId, userId);
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "slug는 영문 소문자·숫자·하이픈 1~64자입니다");
        }
        String title = request.title().strip();
        String content = DiagnosisPrompt.redact(request.content());
        List<KnowledgeText.Chunk> parts = KnowledgeText.split(title, content);
        List<float[]> vectors = embeddings.embed(parts.stream().map(KnowledgeText.Chunk::text).toList());

        KnowledgeDocument document = documents.findByRepositoryIdAndSlug(repo.getId(), slug)
                .map(d -> { d.update(title, content, userId); return d; })
                .orElseGet(() -> new KnowledgeDocument(repo.getId(), slug, title, content, userId));
        documents.saveAndFlush(document);
        replace(documentRef(document.getId()), parts, vectors, repo.getId(), null, Source.DOCUMENT, title,
                "rev " + document.getRevision(), document.getUpdatedAt(), KnowledgeText.sha256(content));
        return new DocumentSummary(slug, title, document.getRevision(), parts.size(), document.getUpdatedAt());
    }

    @Transactional
    public void deleteDocument(Long repositoryId, String slug, Long userId) {
        resourceAccessService.requireRepository(repositoryId, userId);
        KnowledgeDocument document = documents.findByRepositoryIdAndSlug(repositoryId, slug)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.ENTITY_NOT_FOUND));
        chunks.deleteBySourceRef(documentRef(document.getId()));
        documents.delete(document);
    }

    /** 사람이 확정한 원인으로 사건을 만들거나 고친다. 실패하거나 판정 불가인 배포만 받는다 */
    @Transactional
    public IncidentSummary confirmIncident(Long deploymentId, IncidentRequest request, Long userId) {
        Deployment deployment = resourceAccessService.requireDeployment(deploymentId, userId);
        if (deployment.getStatus() != DeploymentStatus.FAILED && deployment.getStatus() != DeploymentStatus.UNKNOWN) {
            throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "실패하거나 판정 불가인 배포만 사건으로 기록합니다");
        }
        if (!DiagnosisLabels.isCategory(request.category()) || !DiagnosisLabels.isRootCause(request.rootCause())) {
            throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "category·root_cause는 진단 라벨 목록에서 고릅니다: "
                    + DiagnosisLabels.CATEGORIES.keySet() + " / " + DiagnosisLabels.ROOT_CAUSES.keySet());
        }
        String resolution = request.resolution() == null ? null : DiagnosisPrompt.redact(request.resolution().strip());
        Long repositoryId = deployment.getSourceRepository().getId();
        String text = incidentText(deployment, request.category(), request.rootCause(), resolution);
        float[] vector = embeddings.embed(List.of(text)).get(0);

        Incident incident = incidents.findByDeploymentId(deploymentId)
                .map(i -> { i.update(request.category(), request.rootCause(), resolution, userId); return i; })
                .orElseGet(() -> new Incident(deploymentId, repositoryId, request.category(), request.rootCause(), resolution, userId));
        incidents.saveAndFlush(incident);
        String commit = deployment.getCommitHash() == null ? "-" : deployment.getCommitHash().substring(0, Math.min(7, deployment.getCommitHash().length()));
        replace(incidentRef(deploymentId), List.of(new KnowledgeText.Chunk("", text)), List.of(vector), repositoryId, deploymentId,
                Source.INCIDENT, "사건: 배포 #" + deploymentId, "commit " + commit + " · " + deployment.getImageUri(),
                incident.getUpdatedAt(), KnowledgeText.sha256(text));
        return new IncidentSummary(deploymentId, incident.getCategory(), incident.getRootCause(), resolution, 1, incident.getUpdatedAt());
    }

    @Transactional
    public void deleteIncident(Long deploymentId, Long userId) {
        resourceAccessService.requireDeployment(deploymentId, userId);
        Incident incident = incidents.findByDeploymentId(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.ENTITY_NOT_FOUND));
        chunks.deleteBySourceRef(incidentRef(deploymentId));
        incidents.delete(incident);
    }

    /**
     * 진단할 배포의 앱과 플랫폼 조각에서 질의와 가까운 지식을 고른다. 진단 중인 배포 자신의 사건은 제외한다.
     * 후보가 없으면 임베딩을 호출하지 않는다. 호출자가 소유권을 확인한 뒤 부른다.
     */
    @Transactional(readOnly = true)
    public List<KnowledgeItem> search(Long repositoryId, String repositoryName, Long deploymentId, String redactedInputJson) {
        if (!enabled || !embeddings.available()) return List.of();
        List<KnowledgeChunk> candidates = chunks.findSearchable(repositoryId).stream()
                .filter(c -> c.getEmbeddingModel().equals(embeddings.model()))
                .filter(c -> !(c.getSourceType() == Source.INCIDENT && deploymentId.equals(c.getDeploymentId())))
                .toList();
        if (candidates.isEmpty()) return List.of();
        float[] query = embeddings.embed(List.of(KnowledgeText.query(redactedInputJson))).get(0);
        return KnowledgeText.top(query, candidates, c -> KnowledgeText.fromBytes(c.getEmbedding()), topK, minSimilarity).stream()
                .map(s -> new KnowledgeItem(s.item().getSourceType().name(), s.item().getSourceRef(), s.item().getSourceTitle(),
                        s.item().getRepositoryId() == null ? "platform" : repositoryName, s.item().getSourceVersion(),
                        s.item().getSourceUpdatedAt(), s.item().getText(), Math.round(s.similarity() * 1000) / 1000.0))
                .toList();
    }

    /** 플랫폼 runbook 파일 하나를 다시 색인한다. 내용·모델이 같으면 건너뛴다 */
    @Transactional
    public boolean indexPlatform(String file, String title, String content) {
        String ref = "platform:" + file;
        String hash = KnowledgeText.sha256(content);
        List<KnowledgeChunk> existing = chunks.findBySourceType(Source.PLATFORM).stream().filter(c -> c.getSourceRef().equals(ref)).toList();
        if (!existing.isEmpty() && existing.stream().allMatch(c -> c.getContentHash().equals(hash) && c.getEmbeddingModel().equals(embeddings.model()))) {
            return false;
        }
        List<KnowledgeText.Chunk> parts = KnowledgeText.split(title, content);
        List<float[]> vectors = embeddings.embed(parts.stream().map(KnowledgeText.Chunk::text).toList());
        replace(ref, parts, vectors, null, null, Source.PLATFORM, title, "sha " + hash.substring(0, 8), LocalDateTime.now(), hash);
        return true;
    }

    /** 사라진 플랫폼 runbook 파일의 조각을 지운다 */
    @Transactional
    public void removePlatformExcept(List<String> files) {
        chunks.findBySourceType(Source.PLATFORM).stream().map(KnowledgeChunk::getSourceRef).distinct()
                .filter(ref -> !files.contains(ref.substring("platform:".length())))
                .forEach(chunks::deleteBySourceRef);
    }

    private void replace(String ref, List<KnowledgeText.Chunk> parts, List<float[]> vectors, Long repositoryId, Long deploymentId,
                         Source source, String title, String version, LocalDateTime updatedAt, String hash) {
        chunks.deleteBySourceRef(ref);
        List<KnowledgeChunk> rows = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            rows.add(new KnowledgeChunk(repositoryId, deploymentId, source, ref, title, version, updatedAt, i,
                    parts.get(i).text(), KnowledgeText.toBytes(vectors.get(i)), embeddings.model(), hash));
        }
        chunks.saveAll(rows);
    }

    static String incidentText(Deployment deployment, String category, String rootCause, String resolution) {
        String commit = deployment.getCommitHash() == null ? "-" : deployment.getCommitHash();
        return DiagnosisPrompt.redact("""
                확정된 과거 사건: 배포 #%d (commit %s, 이미지 %s, 배포 시각 %s)
                실패 종류: %s
                실패 사유: %s
                확정 원인: %s / %s
                해결: %s""".formatted(deployment.getId(), commit, deployment.getImageUri(), deployment.getCreatedAt(),
                deployment.getFailureKind(), deployment.getFailReason(), category, rootCause,
                resolution == null || resolution.isBlank() ? "기록 없음" : resolution));
    }

    static String documentRef(Long documentId) {
        return "doc:" + documentId;
    }

    static String incidentRef(Long deploymentId) {
        return "incident:" + deploymentId;
    }
}
