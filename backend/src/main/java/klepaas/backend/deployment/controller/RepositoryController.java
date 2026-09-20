package klepaas.backend.deployment.controller;

import jakarta.validation.Valid;
import klepaas.backend.auth.config.CustomUserDetails;
import klepaas.backend.deployment.dto.*;
import klepaas.backend.deployment.service.RepositoryService;
import klepaas.backend.global.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/repositories")
@RequiredArgsConstructor
public class RepositoryController {

    private final RepositoryService repositoryService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RepositoryResponse> createRepository(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody CreateRepositoryRequest request) {
        return ApiResponse.success(repositoryService.createRepository(userDetails.getUserId(), request));
    }

    @GetMapping
    public ApiResponse<List<RepositoryResponse>> getRepositories(
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ApiResponse.success(repositoryService.getRepositories(userDetails.getUserId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<RepositoryResponse> getRepository(@PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ApiResponse.success(repositoryService.getRepository(id, userDetails.getUserId()));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteRepository(@PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        repositoryService.deleteRepository(id, userDetails.getUserId());
        return ApiResponse.success(null, "저장소가 삭제되었습니다");
    }

    @GetMapping("/{id}/config")
    public ApiResponse<DeploymentConfigResponse> getDeploymentConfig(@PathVariable Long id,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ApiResponse.success(repositoryService.getDeploymentConfig(id, userDetails.getUserId()));
    }

    @PutMapping("/{id}/config")
    public ApiResponse<DeploymentConfigResponse> updateDeploymentConfig(
            @PathVariable Long id,
            @Valid @RequestBody UpdateDeploymentConfigRequest request,
            @AuthenticationPrincipal CustomUserDetails userDetails) {
        return ApiResponse.success(repositoryService.updateDeploymentConfig(id, request, userDetails.getUserId()));
    }
}
