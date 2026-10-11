package klepaas.backend.ai.service;

import klepaas.backend.ai.entity.CommandLog;
import klepaas.backend.ai.entity.CommandStatus;
import klepaas.backend.ai.repository.CommandLogRepository;
import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.EntityNotFoundException;
import klepaas.backend.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class CommandConfirmationService {
    /** 승인 대기 명령의 유효 시간 */
    static final Duration TTL = Duration.ofMinutes(10);

    private final CommandLogRepository logs;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommandLog claim(Long id, Long userId) {
        return consume(id, userId, CommandStatus.EXECUTING, true);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommandLog cancel(Long id, Long userId) {
        return consume(id, userId, CommandStatus.CANCELLED, false);
    }

    private CommandLog consume(Long id, Long userId, CommandStatus next, boolean confirmed) {
        if (id == null || userId == null) {
            throw new EntityNotFoundException(ErrorCode.COMMAND_LOG_NOT_FOUND);
        }
        CommandLog log = logs.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(ErrorCode.COMMAND_LOG_NOT_FOUND));
        if (log.getUser() == null || !userId.equals(log.getUser().getId())) {
            throw new EntityNotFoundException(ErrorCode.COMMAND_LOG_NOT_FOUND);
        }
        LocalDateTime cutoff = LocalDateTime.now().minus(TTL);
        if (logs.consume(id, userId, CommandStatus.PENDING, next, confirmed, cutoff) != 1) {
            logs.expire(id, userId, CommandStatus.PENDING, CommandStatus.EXPIRED, cutoff);
            return null;
        }
        if (log.getSession() != null) log.getSession().getSessionToken();
        return log;
    }

    /** 이력을 보여 주기 전에 만료된 승인 대기 명령을 EXPIRED로 바꾼다 (#126). 만료는 원래 승인 시도 때만 판정됐다 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int expireStale(Long userId) {
        return logs.expireStale(userId, CommandStatus.PENDING, CommandStatus.EXPIRED, LocalDateTime.now().minus(TTL));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeed(Long id, String result) {
        finish(id, CommandStatus.SUCCEEDED, true, result, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(Long id, String error) {
        finish(id, CommandStatus.FAILED, false, null, error);
    }

    private void finish(Long id, CommandStatus next, boolean executed, String result, String error) {
        if (logs.finish(id, CommandStatus.EXECUTING, next, executed, result, error) != 1) {
            throw new BusinessException(ErrorCode.COMMAND_CONFIRMATION_CONFLICT);
        }
    }
}
