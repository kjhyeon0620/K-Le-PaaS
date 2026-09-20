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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.time.LocalDateTime;
import java.sql.DriverManager;
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
        when(dispatcher.dispatch(any(), eq(userId))).thenAnswer(inv -> {
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
        jdbc.update("update command_log set status = null where id = ?", legacy);
        assertThat(confirmations.claim(legacy, owner)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from command_log where id = ? and status is null", Integer.class, legacy)).isEqualTo(1);
    }

    @Test
    void migrationBackfillsLegacyCombinationsAndIsIdempotent() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:h2:mem:command_migration;DB_CLOSE_DELAY=-1")) {
            try (var statement = db.createStatement()) {
                statement.execute("create table command_log (id int primary key, is_executed boolean, error_message varchar(255), confirmed boolean)");
                statement.execute("insert into command_log values (1, true, null, false), (2, false, 'broken', true), (3, false, null, false), (4, true, 'broken', true), (5, null, null, false)");
            }
            var script = new ClassPathResource("db/manual/command-log-status.sql");
            ScriptUtils.executeSqlScript(db, script);
            db.createStatement().execute("update command_log set status = 'PENDING' where id = 5");
            ScriptUtils.executeSqlScript(db, script);
            try (var rows = db.createStatement().executeQuery("select status from command_log order by id")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("SUCCEEDED");
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("FAILED");
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("UNKNOWN");
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("UNKNOWN");
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("PENDING");
                assertThat(rows.next()).isFalse();
            }
        }
        try (var empty = DriverManager.getConnection("jdbc:h2:mem:command_migration_empty;DB_CLOSE_DELAY=-1")) {
            empty.createStatement().execute("create table command_log (id int primary key, is_executed boolean, error_message varchar(255))");
            ScriptUtils.executeSqlScript(empty, new ClassPathResource("db/manual/command-log-status.sql"));
            assertThat(empty.createStatement().executeQuery("select status from command_log").next()).isFalse();
        }
    }

    private Long pending(Long userId) {
        User user = users.findById(userId).orElseThrow();
        return logs.saveAndFlush(CommandLog.builder().user(user)
                .rawCommand("deploy").interpretedIntent(Intent.DEPLOY).intentArgs("{}")
                .riskLevel(RiskLevel.HIGH).requiresConfirmation(true)
                .session(sessions.save(new ConversationSession(user))).build()).getId();
    }
}
