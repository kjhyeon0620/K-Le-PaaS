package klepaas.backend.auth.controller;

import jakarta.validation.Valid;
import klepaas.backend.auth.config.CustomUserDetails;
import klepaas.backend.auth.dto.OAuthLoginRequest;
import klepaas.backend.auth.dto.TokenResponse;
import klepaas.backend.auth.service.AuthService;
import klepaas.backend.global.dto.ApiResponse;
import klepaas.backend.user.dto.UserResponse;
import klepaas.backend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final UserService userService;

    @GetMapping("/oauth2/url/{provider}")
    public ApiResponse<Map<String, String>> getOAuthUrl(@PathVariable String provider) {
        String url = authService.getOAuthUrl(provider);
        return ApiResponse.success(Map.of("url", url));
    }

    @PostMapping("/oauth2/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody OAuthLoginRequest request) {
        return ApiResponse.success(authService.login(request.code()));
    }

    @GetMapping("/me")
    public ApiResponse<UserResponse> getCurrentUser(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return ApiResponse.success(userService.getUser(userDetails.getUserId()));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@RequestBody Map<String, String> request) {
        String refreshToken = request.get("refresh_token");
        return ApiResponse.success(authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal CustomUserDetails userDetails) {
        authService.logout(userDetails.getUserId());
        return ApiResponse.success(null);
    }
}
