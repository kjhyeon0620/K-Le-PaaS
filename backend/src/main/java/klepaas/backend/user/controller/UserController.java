package klepaas.backend.user.controller;

import klepaas.backend.auth.config.CustomUserDetails;
import klepaas.backend.global.dto.ApiResponse;
import klepaas.backend.user.dto.UserResponse;
import klepaas.backend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ApiResponse<UserResponse> getCurrentUser(@AuthenticationPrincipal CustomUserDetails userDetails) {
        return ApiResponse.success(userService.getUser(userDetails.getUserId()));
    }
}
