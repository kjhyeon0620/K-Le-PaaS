package klepaas.backend.ai.knowledge;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 기동 시 플랫폼 공통 runbook(classpath:knowledge/*.md)을 색인한다 (#104). 내용이 바뀐 파일만 다시 임베딩한다.
 * 키가 없거나 호출이 실패하면 경고만 남기고 기동을 막지 않는다. 진단은 지식 없이 동작한다.
 * 외부 호출이라 별도 스레드에서 돌린다. 기동·readiness를 임베딩 응답 시간에 묶지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformKnowledgeIndexer implements ApplicationRunner {

    private final KnowledgeService knowledgeService;
    private final Embeddings embeddings;

    @Override
    public void run(ApplicationArguments args) {
        if (!knowledgeService.enabled()) {
            log.info("Platform knowledge not indexed: diagnosis.rag.enabled=false");
            return;
        }
        if (!embeddings.available()) {
            log.warn("Platform knowledge not indexed: OPENAI_API_KEY is not set");
            return;
        }
        Thread worker = new Thread(this::index, "platform-knowledge-indexer");
        worker.setDaemon(true);
        worker.start();
    }

    void index() {
        try {
            List<String> files = new ArrayList<>();
            int indexed = 0;
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources("classpath:knowledge/*.md")) {
                String content = resource.getContentAsString(StandardCharsets.UTF_8);
                String file = resource.getFilename();
                files.add(file);
                String title = content.lines().filter(l -> l.startsWith("# ")).findFirst().map(l -> l.substring(2).strip()).orElse(file);
                if (knowledgeService.indexPlatform(file, title, content)) indexed++;
            }
            knowledgeService.removePlatformExcept(files);
            log.info("Platform knowledge: files={}, reindexed={}", files.size(), indexed);
        } catch (Exception e) {
            log.warn("Platform knowledge indexing failed: {}", e.getClass().getSimpleName());
        }
    }
}
