package klepaas.backend.ai.knowledge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 지식 분할·질의문·유사도 (#104). 서버와 평가 하네스가 같은 코드를 쓴다.
 */
public final class KnowledgeText {

    public static final int CHUNK_CHARS = 800;
    private static final int QUERY_LOG_LINES = 8;
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Chunk(String heading, String text) {
    }

    public record Scored<T>(T item, double similarity) {
    }

    /** 마크다운을 제목 단위로 나누고, 길면 문단 단위로 다시 나눈다. 조각마다 문서 제목과 절 제목을 앞에 붙인다. */
    public static List<Chunk> split(String title, String markdown) {
        List<Chunk> chunks = new ArrayList<>();
        String heading = "";
        StringBuilder section = new StringBuilder();
        for (String line : markdown.replace("\r\n", "\n").split("\n")) {
            if (line.startsWith("#")) {
                flush(chunks, title, heading, section.toString());
                heading = line.replaceFirst("^#+\\s*", "").strip();
                section.setLength(0);
            } else {
                section.append(line).append('\n');
            }
        }
        flush(chunks, title, heading, section.toString());
        return chunks;
    }

    private static void flush(List<Chunk> chunks, String title, String heading, String body) {
        String prefix = heading.isEmpty() || heading.equals(title) ? title : title + " > " + heading;
        StringBuilder current = new StringBuilder();
        for (String paragraph : body.strip().split("\n\\s*\n")) {
            // 문단 하나가 조각보다 길면 글자 수로 자른다
            for (int from = 0; from < paragraph.strip().length(); from += CHUNK_CHARS) {
                String piece = paragraph.strip().substring(from, Math.min(paragraph.strip().length(), from + CHUNK_CHARS));
                if (current.length() > 0 && current.length() + piece.length() > CHUNK_CHARS) {
                    chunks.add(new Chunk(prefix, prefix + "\n" + current.toString().strip()));
                    current.setLength(0);
                }
                current.append(piece).append("\n\n");
            }
        }
        if (!current.toString().isBlank()) chunks.add(new Chunk(prefix, prefix + "\n" + current.toString().strip()));
    }

    /** 진단 입력({"detail", "logs"}, 가린 JSON)에서 검색 질의문을 만든다: 실패 종류·사유, 변경, Pod 상태·마지막 로그, 이벤트 */
    public static String query(String redactedInputJson) {
        JsonNode input;
        try {
            input = JSON.readTree(redactedInputJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("진단 입력을 읽을 수 없습니다", e);
        }
        StringBuilder q = new StringBuilder();
        JsonNode deployment = input.path("detail").path("deployment");
        append(q, "repository", deployment.path("repository_name"));
        append(q, "failure_kind", deployment.path("failure_kind"));
        append(q, "fail_reason", deployment.path("fail_reason"));
        for (JsonNode change : input.path("detail").path("changes")) append(q, "change", change.path("field"));
        for (JsonNode pod : input.path("logs").path("pods")) {
            append(q, "pod", pod.path("state_reason"));
            JsonNode lines = pod.path("previous_logs").isArray() && pod.path("previous_logs").size() > 0
                    ? pod.path("previous_logs") : pod.path("logs");
            for (int i = Math.max(0, lines.size() - QUERY_LOG_LINES); i < lines.size(); i++) append(q, "log", lines.get(i));
        }
        for (JsonNode event : input.path("logs").path("events")) {
            String reason = event.path("reason").asText("");
            if (!reason.isEmpty() && !reason.matches("Scheduled|Pulled|Created|Started|SuccessfulCreate|Pulling")) {
                append(q, "event", event.path("message"));
            }
        }
        return q.toString().strip();
    }

    private static void append(StringBuilder q, String label, JsonNode value) {
        if (value != null && value.isValueNode() && !value.asText().isBlank()) q.append(label).append(": ").append(value.asText()).append('\n');
    }

    public static <T> List<Scored<T>> top(float[] query, List<T> items, java.util.function.Function<T, float[]> vector,
                                          int k, double minSimilarity) {
        return items.stream().map(item -> new Scored<>(item, cosine(query, vector.apply(item))))
                .filter(s -> s.similarity() >= minSimilarity)
                .sorted(Comparator.comparingDouble((Scored<T> s) -> s.similarity()).reversed())
                .limit(k).toList();
    }

    public static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return -1;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public static byte[] toBytes(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) buffer.putFloat(v);
        return buffer.array();
    }

    public static float[] fromBytes(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[bytes.length / 4];
        for (int i = 0; i < vector.length; i++) vector[i] = buffer.getFloat();
        return vector;
    }

    public static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private KnowledgeText() {
    }
}
