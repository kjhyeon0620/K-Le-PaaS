package klepaas.backend.ai.usage;

import klepaas.backend.auth.config.CustomUserDetails;
import klepaas.backend.global.dto.ApiResponse;
import klepaas.backend.global.exception.ErrorCode;
import klepaas.backend.global.exception.InvalidRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 본인 LLM 사용량과 오늘 남은 예산 (#176). 다른 사용자 사용량은 볼 수 없다. READ_ONLY 이상(GET 규칙) */
@RestController
@RequestMapping("/api/v1/llm")
@RequiredArgsConstructor
public class LlmUsageController {

    private final LlmUsageService usageService;

    @GetMapping("/usage")
    public ApiResponse<LlmUsageService.UsageSummary> usage(@AuthenticationPrincipal CustomUserDetails userDetails,
                                                          @RequestParam(defaultValue = "7") int days) {
        if (days < 1 || days > 31) throw new InvalidRequestException(ErrorCode.INVALID_REQUEST, "days는 1~31이어야 합니다");
        return ApiResponse.success(usageService.summary(userDetails.getUserId(), days));
    }
}
