package klepaas.backend.infra.dto;

public record BuildStatusResult(
        boolean completed,
        boolean success,
        String imageUri,
        String message
) {}
