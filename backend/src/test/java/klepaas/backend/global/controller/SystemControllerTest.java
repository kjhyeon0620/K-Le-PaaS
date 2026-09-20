package klepaas.backend.global.controller;

import klepaas.backend.auth.config.SecurityConfig;
import klepaas.backend.auth.jwt.JwtAuthenticationFilter;
import klepaas.backend.auth.jwt.JwtTokenProvider;
import klepaas.backend.auth.token.service.CliAccessTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CliAccessTokenService cliAccessTokenService;

    @Test
    @WithAnonymousUser
    void readyIsPublicAndQueriesTheDatabase() throws Exception {
        mockMvc.perform(get("/api/v1/system/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.status").value("UP"));

        verify(jdbcTemplate).queryForObject("SELECT 1", Integer.class);
    }

    @Test
    @WithAnonymousUser
    void readyReturnsGeneric503WhenTheDatabaseIsUnavailable() throws Exception {
        doThrow(new DataAccessResourceFailureException("jdbc password=top-secret"))
                .when(jdbcTemplate).queryForObject("SELECT 1", Integer.class);

        mockMvc.perform(get("/api/v1/system/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.data.status").value("DOWN"))
                .andExpect(jsonPath("$.message").value("Service unavailable"))
                .andExpect(content().string(not(containsString("top-secret"))));
    }

    @Test
    void versionKeepsItsExistingFieldsAndAddsARevision() throws Exception {
        mockMvc.perform(get("/api/v1/system/version"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("0.0.1-SNAPSHOT"))
                .andExpect(jsonPath("$.data.java").isNotEmpty())
                .andExpect(jsonPath("$.data.revision").value("unknown"));
    }
}
