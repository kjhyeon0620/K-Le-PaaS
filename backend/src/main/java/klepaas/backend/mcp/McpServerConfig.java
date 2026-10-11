package klepaas.backend.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import klepaas.backend.auth.config.CustomUserDetails;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;

/**
 * 원격 MCP 서버 (#103). 공식 MCP Java SDK의 세션 없는 Streamable HTTP 서블릿을 /api/v1/mcp에 등록한다.
 * 인증·scope 1차 검사는 기존 Spring Security 필터가 하고(SecurityConfig), 인증한 사용자를 도구에 넘긴다.
 * 프로토콜 메시지는 SDK 기본 JSON 설정(camelCase)을 쓰고, 도구 결과만 REST와 같은 snake_case JSON이다.
 */
@Configuration
public class McpServerConfig {

    public static final String ENDPOINT = "/api/v1/mcp";

    @Bean
    HttpServletStatelessServerTransport mcpTransport() {
        return HttpServletStatelessServerTransport.builder()
                .jsonMapper(McpJsonDefaults.getMapper())
                .messageEndpoint(ENDPOINT)
                // 서블릿 요청 스레드에서 인증 필터가 채운 사용자를 꺼낸다
                .contextExtractor(request -> SecurityContextHolder.getContext().getAuthentication() != null
                        && SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof CustomUserDetails user
                        ? McpTransportContext.create(Map.of(McpTools.USER, user)) : McpTransportContext.EMPTY)
                .build();
    }

    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(HttpServletStatelessServerTransport transport) {
        return new ServletRegistrationBean<>(transport, ENDPOINT);
    }

    @Bean(destroyMethod = "close")
    McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport transport, McpTools tools) {
        return McpServer.sync(transport)
                .serverInfo("k-le-paas", "1.0.0")
                .instructions("K-Le-PaaS 배포 조회·실패 진단·복구 제안 도구. 변경은 제안만 하며, 실행은 사람이 klepaas confirm으로 승인한다.")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .jsonMapper(McpJsonDefaults.getMapper())
                // 입력 스키마(필수 값, 범위, 길이)를 SDK가 검사한다
                .validateToolInputs(true)
                // 서블릿 요청 스레드에서 실행한다 (트랜잭션·로그 맥락이 REST와 같다)
                .immediateExecution(true)
                .tools(tools.specifications())
                .build();
    }
}
