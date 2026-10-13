package klepaas.backend.deployment.dto;

import klepaas.backend.deployment.entity.CloudVendor;
import klepaas.backend.deployment.entity.SourceRepository;

import java.time.LocalDateTime;

public record RepositoryResponse(
        Long id,
        String owner,
        String repoName,
        String gitUrl,
        CloudVendor cloudVendor,
        Long userId,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static RepositoryResponse from(SourceRepository entity) {
        return new RepositoryResponse(
                entity.getId(),
                entity.getOwner(),
                entity.getRepoName(),
                entity.getGitUrl(),
                entity.getCloudVendor(),
                entity.getUser().getId(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
