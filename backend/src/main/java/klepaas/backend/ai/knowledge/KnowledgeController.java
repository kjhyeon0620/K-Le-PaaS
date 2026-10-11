package klepaas.backend.ai.knowledge;

import jakarta.validation.Valid;
import klepaas.backend.ai.knowledge.KnowledgeDtos.*;
import klepaas.backend.auth.config.CustomUserDetails;
import klepaas.backend.global.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** 앱 문서·확정 사건 관리 (#104). 조회는 READ_ONLY 이상, 변경은 FULL (SecurityConfig 기본 규칙) */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @GetMapping("/repositories/{id}/knowledge")
    public ApiResponse<KnowledgeListResponse> list(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(knowledgeService.list(id, user.getUserId()));
    }

    @PutMapping("/repositories/{id}/knowledge/{slug}")
    public ApiResponse<DocumentSummary> putDocument(@PathVariable Long id, @PathVariable String slug,
                                                    @Valid @RequestBody PutDocumentRequest request,
                                                    @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(knowledgeService.putDocument(id, slug, request, user.getUserId()));
    }

    @DeleteMapping("/repositories/{id}/knowledge/{slug}")
    public ApiResponse<Void> deleteDocument(@PathVariable Long id, @PathVariable String slug,
                                            @AuthenticationPrincipal CustomUserDetails user) {
        knowledgeService.deleteDocument(id, slug, user.getUserId());
        return ApiResponse.success(null);
    }

    @PutMapping("/deployments/{id}/incident")
    public ApiResponse<IncidentSummary> confirmIncident(@PathVariable Long id, @Valid @RequestBody IncidentRequest request,
                                                       @AuthenticationPrincipal CustomUserDetails user) {
        return ApiResponse.success(knowledgeService.confirmIncident(id, request, user.getUserId()));
    }

    @DeleteMapping("/deployments/{id}/incident")
    public ApiResponse<Void> deleteIncident(@PathVariable Long id, @AuthenticationPrincipal CustomUserDetails user) {
        knowledgeService.deleteIncident(id, user.getUserId());
        return ApiResponse.success(null);
    }
}
