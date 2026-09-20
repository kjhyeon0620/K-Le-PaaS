package klepaas.backend.global.controller;

import klepaas.backend.global.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectProvider<BuildProperties> buildProperties;

    public SystemController(JdbcTemplate jdbcTemplate, ObjectProvider<BuildProperties> buildProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.buildProperties = buildProperties;
    }

    @Value("${app.version:0.0.1-SNAPSHOT}")
    private String appVersion;

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        return ApiResponse.success(Map.of("status", "UP"));
    }

    @GetMapping("/ready")
    public ResponseEntity<ApiResponse<Map<String, String>>> ready() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(ApiResponse.success(Map.of("status", "UP")));
        } catch (DataAccessException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiResponse<>("error", Map.of("status", "DOWN"), "Service unavailable"));
        }
    }

    @GetMapping("/version")
    public ApiResponse<Map<String, String>> version() {
        String revision = Optional.ofNullable(buildProperties.getIfAvailable())
                .map(properties -> properties.get("revision"))
                .orElse("unknown");
        return ApiResponse.success(Map.of(
                "version", appVersion,
                "java", System.getProperty("java.version"),
                "revision", revision
        ));
    }
}
