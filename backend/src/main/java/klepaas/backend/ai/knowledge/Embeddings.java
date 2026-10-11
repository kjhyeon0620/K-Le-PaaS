package klepaas.backend.ai.knowledge;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.errors.OpenAIException;
import com.openai.models.embeddings.EmbeddingCreateParams;
import klepaas.backend.ai.exception.AiProcessingException;
import klepaas.backend.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 지식 검색용 임베딩 (#104, OpenAI). 키가 없으면 available()이 false이고 호출하면 AI_001이다. */
@Component
public class Embeddings {

    private static final int BATCH = 100;

    private final OpenAIClient client;
    private final String model;

    @Autowired
    public Embeddings(@Value("${OPENAI_API_KEY:}") String apiKey,
                      @Value("${diagnosis.rag.embedding-model:text-embedding-3-small}") String model) {
        // 진단 경로에서도 부르므로 SDK 기본(10분)보다 짧게 끊는다
        this(apiKey == null || apiKey.isBlank() ? null
                : OpenAIOkHttpClient.builder().apiKey(apiKey).timeout(java.time.Duration.ofSeconds(30)).build(), model);
    }

    Embeddings(OpenAIClient client, String model) {
        this.client = client;
        this.model = model;
    }

    public String model() {
        return model;
    }

    public boolean available() {
        return client != null;
    }

    public List<float[]> embed(List<String> texts) {
        if (client == null) throw new AiProcessingException(ErrorCode.AI_API_ERROR, "OpenAI API 키가 설정되지 않아 임베딩할 수 없습니다");
        List<float[]> vectors = new ArrayList<>();
        try {
            for (int from = 0; from < texts.size(); from += BATCH) {
                var response = client.embeddings().create(EmbeddingCreateParams.builder().model(model)
                        .inputOfArrayOfStrings(texts.subList(from, Math.min(texts.size(), from + BATCH))).build());
                response.data().stream().sorted(java.util.Comparator.comparingLong(e -> e.index())).forEach(e -> {
                    float[] v = new float[e.embedding().size()];
                    for (int i = 0; i < v.length; i++) v[i] = e.embedding().get(i);
                    vectors.add(v);
                });
            }
        } catch (OpenAIException e) {
            throw new AiProcessingException(ErrorCode.AI_API_ERROR, "임베딩 호출 실패: " + e.getClass().getSimpleName());
        }
        return vectors;
    }
}
