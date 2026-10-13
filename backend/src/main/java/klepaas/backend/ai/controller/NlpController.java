package klepaas.backend.ai.controller;

import jakarta.validation.Valid;
import klepaas.backend.ai.dto.CommandLogResponse;
import klepaas.backend.ai.dto.NlpCommandRequest;
import klepaas.backend.ai.dto.NlpCommandResponse;
import klepaas.backend.ai.dto.NlpConfirmRequest;
import klepaas.backend.ai.service.NlpCommandService;
import klepaas.backend.auth.config.CustomUserDetails;
import klepaas.backend.global.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/nlp")
@RequiredArgsConstructor
public class NlpController {

    private final NlpCommandService nlpCommandService;

    @PostMapping("/command")
    public ApiResponse<NlpCommandResponse> processCommand(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody NlpCommandRequest request) {
        return ApiResponse.success(nlpCommandService.processCommand(userDetails.getUserId(), request));
    }

    @PostMapping("/confirm")
    public ApiResponse<NlpCommandResponse> confirmCommand(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @Valid @RequestBody NlpConfirmRequest request) {
        return ApiResponse.success(nlpCommandService.confirmCommand(userDetails.getUserId(), request));
    }

    @GetMapping("/history")
    public ApiResponse<Page<CommandLogResponse>> getHistory(
            @AuthenticationPrincipal CustomUserDetails userDetails,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.success(nlpCommandService.getHistory(userDetails.getUserId(), pageable));
    }
}
