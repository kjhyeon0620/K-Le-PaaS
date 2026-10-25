package klepaas.backend.ai.usage;

import io.micrometer.core.instrument.MeterRegistry;
import klepaas.backend.ai.llm.LlmClient;
import klepaas.backend.ai.llm.LlmPricing;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * LLM 사용량 기록과 사용자별 일일 비용 상한 (#176, 스펙 0176).
 * 모델을 부르는 서비스(진단·자연어 명령)가 호출 전에 withinBudget, 호출 뒤에 record·recordFailure를 부른다.
 * 상한은 느슨하다: 검사와 기록 사이에 들어온 동시 요청만큼 넘을 수 있다. 가격이 없는 호출은 비용 0으로 본다.
 * 기록 실패는 결과를 막지 않는다(경고·지표만, 그 호출은 상한 계산에서 빠질 수 있다).
 */
@Slf4j
@Service
public class LlmUsageService {

    /** 일일 경계는 한국 시간 자정 */
    public static final ZoneId BUDGET_ZONE = ZoneId.of("Asia/Seoul");

    private final LlmUsageRepository usages;
    private final LlmPricing pricing;
    private final MeterRegistry meterRegistry;
    private final BigDecimal dailyUsdPerUser;
    private final Clock clock;

    @Autowired
    public LlmUsageService(LlmUsageRepository usages, LlmPricing pricing, MeterRegistry meterRegistry,
                           @Value("${llm.budget.daily-usd-per-user:1.0}") BigDecimal dailyUsdPerUser) {
        this(usages, pricing, meterRegistry, dailyUsdPerUser, Clock.systemDefaultZone());
    }

    LlmUsageService(LlmUsageRepository usages, LlmPricing pricing, MeterRegistry meterRegistry, BigDecimal dailyUsdPerUser, Clock clock) {
        this.usages = usages;
        this.pricing = pricing;
        this.meterRegistry = meterRegistry;
        this.dailyUsdPerUser = dailyUsdPerUser;
        this.clock = clock;
    }

    public BigDecimal dailyLimitUsd() {
        return dailyUsdPerUser;
    }

    /** 오늘(한국 시간) 사용액이 상한 미만인가. 상한이 0 이하면 상한 없음. 넘었으면 초과 지표를 센다 */
    public boolean withinBudget(Long userId, String feature) {
        if (dailyUsdPerUser.signum() <= 0) return true;
        boolean within = usages.sumCostSince(userId, startOfToday()).compareTo(dailyUsdPerUser) < 0;
        if (!within) {
            meterRegistry.counter("klepaas.llm.budget.exceeded", "feature", feature).increment();
            log.info("LLM budget exceeded: userId={}, feature={}, dailyUsd={}", userId, feature, dailyUsdPerUser);
        }
        return within;
    }

    /** 응답을 받은 호출(파싱 실패도 과금되므로 여기). 비용과 비용 지표를 함께 남긴다 */
    public void record(Long userId, String feature, LlmClient client, LlmClient.Response response, Long deploymentId) {
        try {
            recordSuccess(userId, feature, client, response, deploymentId);
        } catch (RuntimeException e) {
            recordFailed(userId, feature, e);
        }
    }

    private void recordSuccess(Long userId, String feature, LlmClient client, LlmClient.Response response, Long deploymentId) {
        LlmClient.Usage u = response.usage();
        BigDecimal cost = pricing.cost(client.model(), u).orElse(null);
        if (cost != null) {
            meterRegistry.counter("klepaas.llm.cost.usd", "feature", feature, "provider", client.provider(), "model", client.model())
                    .increment(cost.doubleValue());
        } else {
            meterRegistry.counter("klepaas.llm.unpriced.calls", "model", client.model()).increment();
        }
        save(new LlmUsage(userId, feature, client.provider(), client.model(), "success",
                u == null ? null : u.inputTokens(), u == null ? null : u.cachedInputTokens(),
                u == null ? null : u.outputTokens(), u == null ? null : u.reasoningTokens(), cost, response.latencyMs(), deploymentId));
    }

    /** 공급사 호출 실패. 토큰·비용 없이 시도만 남긴다 */
    public void recordFailure(Long userId, String feature, LlmClient client, Long deploymentId) {
        try {
            save(new LlmUsage(userId, feature, client.provider(), client.model(), "error", null, null, null, null, null, null, deploymentId));
        } catch (RuntimeException e) {
            recordFailed(userId, feature, e);
        }
    }

    private void save(LlmUsage usage) {
        usages.save(usage);
    }

    /** 기록 실패는 진단·명령 결과를 막지 않는다. 그 호출은 예산 계산에서 빠질 수 있다 */
    private void recordFailed(Long userId, String feature, RuntimeException e) {
        meterRegistry.counter("klepaas.llm.usage.record.failures").increment();
        log.warn("LLM usage not recorded: userId={}, feature={}, error={}", userId, feature, e.getClass().getSimpleName());
    }

    /** 본인 사용량: 최근 days일(오늘 포함, 한국 시간)의 일별·기능별 합계와 오늘 남은 예산 */
    public UsageSummary summary(Long userId, int days) {
        LocalDate today = LocalDate.now(clock.withZone(BUDGET_ZONE));
        LocalDate first = today.minusDays(days - 1L);
        Map<LocalDate, Map<String, Totals>> byDay = new TreeMap<>();
        for (LlmUsage u : usages.findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(userId, toLocal(first))) {
            LocalDate day = u.getCreatedAt().atZone(clock.getZone()).withZoneSameInstant(BUDGET_ZONE).toLocalDate();
            byDay.computeIfAbsent(day, d -> new LinkedHashMap<>()).computeIfAbsent(u.getFeature(), f -> new Totals()).add(u);
        }
        BigDecimal spentToday = byDay.getOrDefault(today, Map.of()).values().stream()
                .map(t -> t.costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<DayUsage> rows = byDay.entrySet().stream().flatMap(d -> d.getValue().entrySet().stream()
                .map(f -> new DayUsage(d.getKey(), f.getKey(), f.getValue().calls, f.getValue().errors, f.getValue().inputTokens,
                        f.getValue().outputTokens, f.getValue().costUsd, f.getValue().unpricedCalls))).toList();
        BigDecimal remaining = dailyUsdPerUser.signum() <= 0 ? null : dailyUsdPerUser.subtract(spentToday).max(BigDecimal.ZERO);
        return new UsageSummary(BUDGET_ZONE.getId(), dailyUsdPerUser.signum() <= 0 ? null : dailyUsdPerUser, spentToday, remaining, rows);
    }

    /** 오늘(한국 시간) 0시를 저장 시각(JVM 기본 시간대의 LocalDateTime)으로 */
    LocalDateTime startOfToday() {
        return toLocal(LocalDate.now(clock.withZone(BUDGET_ZONE)));
    }

    private LocalDateTime toLocal(LocalDate day) {
        return day.atStartOfDay(BUDGET_ZONE).withZoneSameInstant(clock.getZone()).toLocalDateTime();
    }

    public record DayUsage(LocalDate date, String feature, long calls, long errors, long inputTokens, long outputTokens,
                           BigDecimal costUsd, long unpricedCalls) {
    }

    /** dailyLimitUsd·remainingTodayUsd는 상한이 없으면 null */
    public record UsageSummary(String timezone, BigDecimal dailyLimitUsd, BigDecimal spentTodayUsd, BigDecimal remainingTodayUsd,
                               List<DayUsage> days) {
    }

    private static final class Totals {
        long calls, errors, inputTokens, outputTokens, unpricedCalls;
        BigDecimal costUsd = BigDecimal.ZERO;

        void add(LlmUsage u) {
            calls++;
            if ("error".equals(u.getOutcome())) errors++;
            inputTokens += u.getInputTokens() == null ? 0 : u.getInputTokens();
            outputTokens += u.getOutputTokens() == null ? 0 : u.getOutputTokens();
            if (u.getCostUsd() != null) costUsd = costUsd.add(u.getCostUsd());
            else if ("success".equals(u.getOutcome())) unpricedCalls++;
        }
    }
}
