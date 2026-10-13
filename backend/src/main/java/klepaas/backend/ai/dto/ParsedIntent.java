package klepaas.backend.ai.dto;

import klepaas.backend.ai.entity.Intent;

import java.util.Map;

public record ParsedIntent(
        Intent intent,
        Map<String, Object> args,
        double confidence,
        String message
) {
}
