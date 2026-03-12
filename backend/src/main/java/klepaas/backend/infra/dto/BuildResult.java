package klepaas.backend.infra.dto;

public record BuildResult(
        String externalBuildId,
        String trackingUrl,   // projectId
        String imageUri       // 빌드 트리거 시점에 계산: {registryEndpoint}/{owner}-{repoName}:{shortSha}
) {}
