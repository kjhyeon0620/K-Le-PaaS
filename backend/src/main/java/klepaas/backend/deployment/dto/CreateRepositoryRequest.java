package klepaas.backend.deployment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import klepaas.backend.deployment.entity.CloudVendor;

public record CreateRepositoryRequest(
        @NotBlank String owner,
        @NotBlank String repoName,
        @NotBlank String gitUrl,
        @NotNull CloudVendor cloudVendor,
        String domainUrl
) {
    public CreateRepositoryRequest(String owner, String repoName, String gitUrl, CloudVendor cloudVendor) {
        this(owner, repoName, gitUrl, cloudVendor, null);
    }
}
