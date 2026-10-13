package klepaas.backend.ai.exception;

import klepaas.backend.global.exception.BusinessException;
import klepaas.backend.global.exception.ErrorCode;

public class AiProcessingException extends BusinessException {

    public AiProcessingException(ErrorCode errorCode) {
        super(errorCode);
    }

    public AiProcessingException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }
}
