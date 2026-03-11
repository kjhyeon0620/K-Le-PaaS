package klepaas.backend.cost.controller;

import jakarta.validation.Valid;
import klepaas.backend.cost.dto.CostCheckRequest;
import klepaas.backend.cost.dto.CostDiffRequest;
import klepaas.backend.cost.dto.CostEstimateResponse;
import klepaas.backend.cost.dto.CostPlanRequest;
import klepaas.backend.cost.service.CostEstimationService;
import klepaas.backend.global.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/cost")
@RequiredArgsConstructor
public class CostController {

    private final CostEstimationService costEstimationService;

    @PostMapping("/plan")
    public ApiResponse<CostEstimateResponse> plan(@Valid @RequestBody CostPlanRequest request) {
        return ApiResponse.success(costEstimationService.plan(request));
    }

    @PostMapping("/diff")
    public ApiResponse<CostEstimateResponse> diff(@Valid @RequestBody CostDiffRequest request) {
        return ApiResponse.success(costEstimationService.diff(request));
    }

    @PostMapping("/explain")
    public ApiResponse<CostEstimateResponse> explain(@Valid @RequestBody CostPlanRequest request) {
        return ApiResponse.success(costEstimationService.explain(request));
    }

    @PostMapping("/check")
    public ApiResponse<CostEstimateResponse> check(@Valid @RequestBody CostCheckRequest request) {
        return ApiResponse.success(costEstimationService.check(request));
    }
}
