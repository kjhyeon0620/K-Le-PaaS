package klepaas.backend.deployment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateDeploymentRequest(
        @NotNull Long repositoryId,
        @NotBlank String branchName,
        @Pattern(regexp = "^[a-fA-F0-9]{7,40}$|^HEAD$", message = "commitHash는 7~40자의 hex 문자열 또는 HEAD이어야 합니다")
        @NotBlank String commitHash
) {
}
