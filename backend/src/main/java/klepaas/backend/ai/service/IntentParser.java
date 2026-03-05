package klepaas.backend.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import klepaas.backend.ai.dto.ParsedIntent;
import klepaas.backend.ai.entity.Intent;
import klepaas.backend.ai.exception.AiProcessingException;
import klepaas.backend.global.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class IntentParser {

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("```(?:json)?\\s*\\n?(.*?)\\n?```", Pattern.DOTALL);
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ParsedIntent parse(String geminiResponseText) {
        String jsonStr = extractJson(geminiResponseText);

        try {
            Map<String, Object> parsed = objectMapper.readValue(jsonStr, new TypeReference<>() {});

            String intentStr = ((String) parsed.getOrDefault("intent", "UNKNOWN")).toUpperCase();
            Intent intent;
            try {
                intent = Intent.valueOf(intentStr);
            } catch (IllegalArgumentException e) {
                intent = Intent.UNKNOWN;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> args = (Map<String, Object>) parsed.getOrDefault("args", Map.of());
            double confidence = ((Number) parsed.getOrDefault("confidence", 0.0)).doubleValue();
            String message = (String) parsed.getOrDefault("message", "");

            return new ParsedIntent(intent, args, confidence, message);
        } catch (Exception e) {
            log.error("AI 응답 파싱 실패: {}", geminiResponseText, e);
            throw new AiProcessingException(ErrorCode.AI_PARSE_ERROR,
                    "AI 응답 파싱 실패: " + e.getMessage());
        }
    }

    private String extractJson(String text) {
        if (text == null || text.isBlank()) {
            throw new AiProcessingException(ErrorCode.AI_PARSE_ERROR, "AI 응답이 비어 있습니다");
        }

        // 마크다운 코드블록 안에 JSON이 있는 경우
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // 코드블록 없이 바로 JSON인 경우
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }

        // 코드블록 닫힘 없이 잘린 경우: { ... } 범위 직접 추출
        int jsonStart = text.indexOf('{');
        if (jsonStart >= 0) {
            int jsonEnd = text.lastIndexOf('}');
            if (jsonEnd > jsonStart) {
                return text.substring(jsonStart, jsonEnd + 1).trim();
            }
        }

        throw new AiProcessingException(ErrorCode.AI_PARSE_ERROR,
                "AI 응답에서 JSON을 추출할 수 없습니다: " + text.substring(0, Math.min(text.length(), 100)));
    }
}
