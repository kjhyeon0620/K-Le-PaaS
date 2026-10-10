package klepaas.backend.ai.service;

import klepaas.backend.ai.entity.*;
import klepaas.backend.ai.repository.CommandLogRepository;
import klepaas.backend.ai.repository.ConversationSessionRepository;
import klepaas.backend.ai.client.GeminiClient;
import klepaas.backend.ai.dto.NlpConfirmRequest;
import klepaas.backend.user.entity.Role;
import klepaas.backend.user.entity.User;
import klepaas.backend.user.repository.UserRepository;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.config.JpaConfig;
import klepaas.backend.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({CommandConfirmationService.class, NlpCommandService.class, JpaConfig.class})
class CommandConfirmationConcurrencyTest {
    @Autowired private CommandConfirmationService confirmations;
    @Autowired private NlpCommandService commands;
    @Autowired private CommandLogRepository logs;
    @Autowired private ConversationSessionRepository sessions;
    @Autowired private UserRepository users;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private GeminiClient geminiClient;
    @MockitoBean private IntentParser intentParser;
    @MockitoBean private ActionDispatcher dispatcher;

    @Test
    void twoApprovalsClaimOnceAndCommitBeforeExternalWork() throws Exception {
        Long userId = users.save(User.builder().name("owner").email("owner@example.com").role(Role.USER).build()).getId();
        Long id = pending(userId);
        AtomicInteger dispatched = new AtomicInteger();
        when(dispatcher.dispatch(any(), eq(userId), any())).thenAnswer(inv -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("select status from command_log where id = ?", String.class, id))
                    .isEqualTo("EXECUTING");
            dispatched.incrementAndGet();
            return "ok";
        });
        CountDownLatch go = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var approve = (java.util.concurrent.Callable<Boolean>) () -> {
                go.await();
                try {
                    assertThat(commands.confirmCommand(userId, new NlpConfirmRequest(id, true)).sessionId()).isNotBlank();
                    return true;
                } catch (BusinessException e) {
                    return false;
                }
            };
            var first = pool.submit(approve);
            var second = pool.submit(approve);
            go.countDown();
            assertThat((first.get(10, TimeUnit.SECONDS) ? 1 : 0) + (second.get(10, TimeUnit.SECONDS) ? 1 : 0)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(dispatched).hasValue(1);
        assertThat(confirmations.claim(id, userId)).isNull();
        assertThat(logs.findById(id).orElseThrow().getStatus()).isEqualTo(CommandStatus.SUCCEEDED);
    }

    @Test
    void ownerExpiryCancellationAndLowRiskFailClosed() {
        Long owner = users.save(User.builder().name("owner").email("owner2@example.com").role(Role.USER).build()).getId();
        Long stranger = users.save(User.builder().name("stranger").email("stranger@example.com").role(Role.USER).build()).getId();
        Long id = pending(owner);
        assertThatThrownBy(() -> confirmations.claim(id, stranger))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMAND_LOG_NOT_FOUND));
        assertThatThrownBy(() -> confirmations.claim(id, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMAND_LOG_NOT_FOUND));
        assertThatThrownBy(() -> confirmations.claim(null, owner))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMMAND_LOG_NOT_FOUND));
        assertThat(logs.findById(id).orElseThrow().getStatus()).isEqualTo(CommandStatus.PENDING);
        confirmations.cancel(id, owner);
        assertThat(confirmations.claim(id, owner)).isNull();

        Long expired = pending(owner);
        jdbc.update("update command_log set created_at = ? where id = ?", LocalDateTime.now().minusMinutes(11), expired);
        assertThat(confirmations.claim(expired, owner)).isNull();
        assertThat(logs.findById(expired).orElseThrow().getStatus()).isEqualTo(CommandStatus.EXPIRED);

        var low = logs.saveAndFlush(CommandLog.builder().user(users.findById(owner).orElseThrow())
                .rawCommand("help").interpretedIntent(Intent.HELP).riskLevel(RiskLevel.LOW)
                .requiresConfirmation(false).build());
        assertThat(confirmations.claim(low.getId(), owner)).isNull();

        Long legacy = pending(owner);
        jdbc.update("update command_log set status = ? where id = ?", CommandStatus.UNKNOWN.name(), legacy);
        assertThat(confirmations.claim(legacy, owner)).isNull();
        assertThat(logs.findById(legacy).orElseThrow().getStatus()).isEqualTo(CommandStatus.UNKNOWN);
    }

    private Long pending(Long userId) {
        User user = users.findById(userId).orElseThrow();
        return logs.saveAndFlush(CommandLog.builder().user(user)
                .rawCommand("deploy").interpretedIntent(Intent.DEPLOY).intentArgs("{}")
                .riskLevel(RiskLevel.HIGH).requiresConfirmation(true)
                .session(sessions.save(new ConversationSession(user))).build()).getId();
    }
}
