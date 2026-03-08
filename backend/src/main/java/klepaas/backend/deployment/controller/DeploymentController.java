package klepaas.backend.deployment.controller;

import jakarta.validation.Valid;
import klepaas.backend.auth.config.CustomUserDetails;
import klepaas.backend.deployment.dto.*;
import klepaas.backend.deployment.service.DeploymentService;
import klepaas.backend.global.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class DeploymentController {

    private final DeploymentService deploymentService;

    @PostMapping("/deployments")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DeploymentResponse> createDeployment(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody CreateDeploymentRequest request) {
        return ApiResponse.success(deploymentService.createDeployment(request, userDetails.getUserId()));
    }

    @GetMapping("/deployments")
    public ApiResponse<Page<DeploymentResponse>> getDeployments(
            @RequestParam Long repositoryId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.success(deploymentService.getDeployments(repositoryId, pageable));
    }

    @GetMapping("/deployments/{id}")
    public ApiResponse<DeploymentResponse> getDeployment(@PathVariable Long id) {
        return ApiResponse.success(deploymentService.getDeployment(id));
    }

    @GetMapping("/deployments/{id}/status")
    public ApiResponse<DeploymentStatusResponse> getDeploymentStatus(@PathVariable Long id) {
        return ApiResponse.success(deploymentService.getDeploymentStatus(id));
    }

    @GetMapping("/deployments/{id}/logs")
    public ApiResponse<DeploymentLogResponse> getDeploymentLogs(@PathVariable Long id) {
        return ApiResponse.success(deploymentService.getDeploymentLogs(id));
    }

    @PostMapping("/deployments/{id}/scale")
    public ApiResponse<Void> scaleDeployment(@PathVariable Long id,
                                              @Valid @RequestBody ScaleRequest request) {
        deploymentService.scaleDeployment(id, request);
        return ApiResponse.success(null, "스케일링 요청이 접수되었습니다");
    }

    @PostMapping("/deployments/{id}/restart")
    public ApiResponse<Void> restartDeployment(@PathVariable Long id) {
        deploymentService.restartDeployment(id);
        return ApiResponse.success(null, "재시작 요청이 접수되었습니다");
    }

    @GetMapping("/repositories/{repositoryId}/scaling-history")
    public ApiResponse<Page<ScalingHistoryResponse>> getScalingHistory(
            @PathVariable Long repositoryId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.success(deploymentService.getScalingHistory(repositoryId, pageable));
    }
}
