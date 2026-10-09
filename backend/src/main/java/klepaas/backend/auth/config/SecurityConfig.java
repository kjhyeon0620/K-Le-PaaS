package klepaas.backend.auth.config;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import klepaas.backend.auth.jwt.JwtAuthenticationFilter;
import klepaas.backend.auth.token.entity.CliTokenScope;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String FULL = CliTokenScope.FULL.authority();
    private static final String READ_ONLY = CliTokenScope.READ_ONLY.authority();
    private static final String PROPOSE_ONLY = CliTokenScope.PROPOSE_ONLY.authority();
    private static final String DEPLOY = CliTokenScope.DEPLOY.authority();
    private static final String GITHUB_ACTIONS = CustomUserDetails.GITHUB_ACTIONS_AUTHORITY;
    // 사용자 계정이 있는 주체(Web JWT와 모든 CLI 토큰). GitHub Actions OIDC 주체는 제외한다
    private static final String[] USER_SCOPES = {READ_ONLY, PROPOSE_ONLY, DEPLOY, FULL};

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    @Value("${cors.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // sendError(401/403)의 /error 재디스패치는 필터 인증이 없으므로 원래 상태 코드를 유지하도록 허용
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/api/v1/auth/me").hasAnyAuthority(USER_SCOPES)
                        .requestMatchers("/api/v1/auth/logout").hasAnyAuthority(USER_SCOPES)
                        // CLI 로그인 승인은 토큰 발급과 같으므로 전체 권한만 허용한다(권한 상승 방지)
                        .requestMatchers("/api/v1/cli-auth/sessions/*/approve").hasAuthority(FULL)
                        .requestMatchers("/api/v1/cli-auth/sessions/*/reject").hasAuthority(FULL)
                        .requestMatchers("/api/v1/users/me").hasAnyAuthority(USER_SCOPES)
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/cli-auth/sessions/**").permitAll()
                        .requestMatchers("/api/v1/system/**").permitAll()
                        .requestMatchers("/api/v1/webhooks/**").permitAll()
                        .requestMatchers("/api/v1/ws/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // CLI 토큰 scope: 아래에 없는 변경(토큰 발급·폐기 포함)은 FULL만 허용
                        .requestMatchers(HttpMethod.POST, "/api/v1/deployments").hasAnyAuthority(FULL, DEPLOY, GITHUB_ACTIONS) // 저장소는 컨트롤러에서 확인
                        .requestMatchers(HttpMethod.GET, "/**").hasAnyAuthority(READ_ONLY, PROPOSE_ONLY, FULL)
                        .requestMatchers(HttpMethod.POST, "/api/v1/cost/**").hasAnyAuthority(READ_ONLY, PROPOSE_ONLY, FULL)
                        .requestMatchers(HttpMethod.POST, "/api/v1/nlp/command").hasAnyAuthority(PROPOSE_ONLY, FULL)
                        .anyRequest().hasAuthority(FULL)
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, authException) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.asList(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
