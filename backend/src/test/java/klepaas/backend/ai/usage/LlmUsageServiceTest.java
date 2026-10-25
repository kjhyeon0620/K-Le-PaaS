package klepaas.backend.ai.usage;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import klepaas.backend.ai.llm.LlmClient;
import klepaas.backend.ai.llm.LlmPricing;
import klepaas.backend.global.config.JpaConfig;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** V11 LLM 사용량과 일일 예산 (#176): 비용 계산·기록, 한국 시간 자정 경계, 가격 없는 호출, 조회 합계 */
@DataJpaTest
@Import(JpaConfig.class)
class LlmUsageServiceTest {

    @Autowired private EntityManager em;
    @Autowired private LlmUsageRepository repository;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private Long userId;
    private Long otherId;

    private static LlmClient client(String provider, String model) {
        return new LlmClient() {
            public String provider() { return provider; }
            public String model() { return model; }
            public Response completeJson(String prompt) { throw new UnsupportedOperationException(); }
        };
    }

    private static final LlmClient LUNA = client("openai", "gpt-6-luna");
    // gpt-6-luna: (2000−1000)×0.10 + 1000×0.01 + 500×0.50 = 360 → $0.000360
    private static final LlmClient.Response RESPONSE = new LlmClient.Response("{}", "gpt-6-luna", new LlmClient.Usage(2000, 500, 1000L, 200L), 4000);

    private LlmUsageService service(String limit) {
        return new LlmUsageService(repository, new LlmPricing(), meters, new BigDecimal(limit), Clock.systemDefaultZone());
    }

    @BeforeEach
    void users() {
        User user = User.builder().name("u").email("u@test").role(Role.USER).providerId("1").build();
        User other = User.builder().name("o").email("o@test").role(Role.USER).providerId("2").build();
        em.persist(user);
        em.persist(other);
        userId = user.getId();
        otherId = other.getId();
    }

    @Test
    void recordsCostAndEnforcesDailyLimitPerUser() {
        LlmUsageService s = service("0.001");
        assertThat(s.withinBudget(userId, "nlp")).isTrue();
        s.record(userId, "nlp", LUNA, RESPONSE, null);
        s.record(userId, "diagnosis", LUNA, RESPONSE, 7L);
        assertThat(s.withinBudget(userId, "nlp")).isTrue(); // 0.000720 < 0.001
        s.record(userId, "nlp", LUNA, RESPONSE, null);
        assertThat(s.withinBudget(userId, "nlp")).isFalse(); // 0.001080 ≥ 0.001
        assertThat(s.withinBudget(otherId, "nlp")).isTrue(); // 다른 사용자는 따로 센다

        var row = repository.findAll().stream().filter(u -> "diagnosis".equals(u.getFeature())).findFirst().orElseThrow();
        assertThat(row.getCostUsd()).isEqualByComparingTo("0.000360");
        assertThat(row.getDeploymentId()).isEqualTo(7L);
        assertThat(row.getOutcome()).isEqualTo("success");
        assertThat(meters.find("klepaas.llm.cost.usd").tag("feature", "nlp").counter().count()).isEqualTo(0.00072, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(meters.find("klepaas.llm.budget.exceeded").tag("feature", "nlp").counter().count()).isEqualTo(1);
    }

    @Test
    void usageBeforeKoreanMidnightDoesNotCountToday() {
        LlmUsageService s = service("0.0005");
        s.record(userId, "nlp", LUNA, RESPONSE, null);
        s.record(userId, "nlp", LUNA, RESPONSE, null);
        assertThat(s.withinBudget(userId, "nlp")).isFalse();
        // 두 행을 오늘(한국 시간) 0시 1초 전으로 옮기면 오늘 사용액에서 빠진다
        em.flush();
        em.createNativeQuery("update llm_usages set created_at = :t").setParameter("t", s.startOfToday().minusSeconds(1)).executeUpdate();
        em.clear();
        assertThat(s.withinBudget(userId, "nlp")).isTrue();
    }

    @Test
    void unpricedAndFailedCallsAreRecordedWithoutCost() {
        LlmUsageService s = service("0.0001");
        LlmClient gemma = client("openai-compatible", "gemma4:26b-a4b");
        s.record(userId, "diagnosis", gemma, new LlmClient.Response("{}", "gemma4", new LlmClient.Usage(5000, 300, null, null), 15000), 3L);
        s.recordFailure(userId, "nlp", LUNA, null);
        // 가격 없는 호출은 상한 계산에서 0
        assertThat(s.withinBudget(userId, "diagnosis")).isTrue();
        assertThat(meters.find("klepaas.llm.unpriced.calls").tag("model", "gemma4:26b-a4b").counter().count()).isEqualTo(1);

        var summary = s.summary(userId, 7);
        assertThat(summary.timezone()).isEqualTo("Asia/Seoul");
        assertThat(summary.spentTodayUsd()).isEqualByComparingTo("0");
        assertThat(summary.days()).extracting(d -> d.feature() + ":" + d.calls() + ":" + d.errors() + ":" + d.unpricedCalls())
                .containsExactlyInAnyOrder("diagnosis:1:0:1", "nlp:1:1:0");
        assertThat(summary.days()).allSatisfy(d -> assertThat(d.date()).isEqualTo(LocalDate.now(LlmUsageService.BUDGET_ZONE)));
    }

    @Test
    void zeroLimitMeansNoLimitAndSummaryShowsOnlyOwnUsage() {
        LlmUsageService s = service("0");
        for (int i = 0; i < 3; i++) s.record(userId, "nlp", LUNA, RESPONSE, null);
        s.record(otherId, "nlp", LUNA, RESPONSE, null);
        assertThat(s.withinBudget(userId, "nlp")).isTrue();

        var summary = s.summary(userId, 1);
        assertThat(summary.dailyLimitUsd()).isNull();
        assertThat(summary.remainingTodayUsd()).isNull();
        assertThat(summary.spentTodayUsd()).isEqualByComparingTo("0.001080");
        assertThat(summary.days()).singleElement().satisfies(d -> {
            assertThat(d.calls()).isEqualTo(3);
            assertThat(d.inputTokens()).isEqualTo(6000);
        });
    }

    @Test
    void recordingProblemsNeverPropagate() {
        LlmUsageService s = service("1");
        // 모델 이름이 없는 클라이언트(가격 조회·태그·not null 제약 실패)도 예외를 밖으로 내지 않는다
        LlmClient broken = client(null, null);
        s.record(userId, "nlp", broken, RESPONSE, null);
        s.recordFailure(userId, "nlp", broken, null);
        assertThat(meters.find("klepaas.llm.usage.record.failures").counter().count()).isEqualTo(2);
    }
}
