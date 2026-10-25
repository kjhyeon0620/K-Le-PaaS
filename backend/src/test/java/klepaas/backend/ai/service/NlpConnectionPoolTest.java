package klepaas.backend.ai.service;

import klepaas.backend.ai.dto.NlpCommandRequest;
import klepaas.backend.ai.llm.LlmClient;
import klepaas.backend.global.config.JpaConfig;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 자연어 명령 한 건은 DB 연결을 한 번에 하나만 쥔다 (#184). 둘을 쥐면 동시 요청이 연결 풀 크기에 이를 때
 * 모두가 두 번째 연결을 기다리다 30초 뒤 함께 실패한다(#182 F1). 스레드별로 동시에 연 연결 수의 최대를 센다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({NlpCommandService.class, NameTargets.class, ActionDispatcher.class, IntentParser.class, CommandConfirmationService.class, JpaConfig.class,
        klepaas.backend.ai.usage.LlmUsageService.class, klepaas.backend.ai.llm.LlmPricing.class, NlpConnectionPoolTest.Config.class})
class NlpConnectionPoolTest {

    /** 이 스레드가 지금 연 연결 수와 그 최대 */
    static final ThreadLocal<int[]> OPEN = ThreadLocal.withInitial(() -> new int[2]);

    @TestConfiguration
    static class Config {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        static BeanPostProcessor countingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof DataSource ds ? counting(ds) : bean;
                }
            };
        }
    }

    static DataSource counting(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class}, (p, m, args) -> {
            Object result = invoke(target, m, args);
            if (!m.getName().equals("getConnection")) return result;
            int[] open = OPEN.get();
            open[1] = Math.max(open[1], ++open[0]);
            Connection c = (Connection) result;
            boolean[] closed = {false};
            return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (cp, cm, cargs) -> {
                if (cm.getName().equals("close") && !closed[0]) {
                    closed[0] = true;
                    OPEN.get()[0]--;
                }
                return invoke(c, cm, cargs);
            });
        });
    }

    private static Object invoke(Object target, java.lang.reflect.Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Autowired private NlpCommandService commands;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean(name = "nlpLlm") private LlmClient nlpLlm;
    @MockitoBean private ApprovalTargetResolver approvalTargets;
    @MockitoBean private klepaas.backend.deployment.service.ResourceAccessService resourceAccessService;
    @MockitoBean private klepaas.backend.deployment.service.DeploymentService deploymentService;
    @MockitoBean private KubectlService kubectlService;
    @MockitoBean private klepaas.backend.deployment.service.RecoveryService recoveryService;

    @Test
    void lowRiskCommandHoldsAtMostOneConnectionAtATime() {
        Long userId = users.save(User.builder().name("pool").email("pool@example.com").role(Role.USER).build()).getId();
        jdbc.update("insert into repositories(created_at,updated_at,user_id,git_url,owner,repo_name,cloud_vendor) "
                + "values(now(),now(),?,'https://example.test/a/b','a','b','ON_PREMISE')", userId);
        when(nlpLlm.provider()).thenReturn("openai");
        when(nlpLlm.model()).thenReturn("gpt-6-luna");
        when(nlpLlm.completeJson(anyString())).thenReturn(new LlmClient.Response(
                "{\"intent\":\"LIST_REPOSITORIES\",\"args\":{},\"confidence\":0.9,\"message\":\"\"}", "fake", new LlmClient.Usage(1L, 1L, null, null), 1));
        OPEN.set(new int[2]);

        var response = commands.processCommand(userId, new NlpCommandRequest("저장소 목록", null));

        assertThat(OPEN.get()[1]).as("processCommand가 동시에 쥔 DB 연결 수의 최대").isEqualTo(1);
        assertThat(OPEN.get()[0]).as("끝난 뒤 남은 연결").isZero();
        assertThat(jdbc.queryForObject("select status from command_log where id = ?", String.class, response.commandLogId()))
                .isEqualTo("SUCCEEDED");
    }
}
