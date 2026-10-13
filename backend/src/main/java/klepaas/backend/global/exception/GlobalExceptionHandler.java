package klepaas.backend.global.exception;

import klepaas.backend.global.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(GitHubAppInstallationRequiredException.class)
    public ResponseEntity<ErrorResponse> handleGitHubAppInstallationRequired(
            GitHubAppInstallationRequiredException e, HttpServletRequest request) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("GitHub App installation required: {}", e.getMessage());

        ErrorResponse response = new ErrorResponse(
                errorCode.name(),
                errorCode.getCode(),
                e.getMessage(),
                request.getRequestURI(),
                e.getInstallUrl()
        );
        return ResponseEntity.status(errorCode.getHttpStatus()).body(response);
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusinessException(BusinessException e, HttpServletRequest request) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("Business exception: {} - {}", errorCode.getCode(), e.getMessage());

        ErrorResponse response = new ErrorResponse(
                errorCode.name(),
                errorCode.getCode(),
                e.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(errorCode.getHttpStatus()).body(response);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationException(MethodArgumentNotValidException e,
                                                                    HttpServletRequest request) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining(", "));

        log.warn("Validation exception: {}", message);

        ErrorResponse response = new ErrorResponse(
                "VALIDATION_ERROR",
                "COMMON_003",
                message,
                request.getRequestURI()
        );
        return ResponseEntity.badRequest().body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleException(Exception e, HttpServletRequest request) {
        log.error("Unexpected exception: ", e);

        ErrorCode errorCode = ErrorCode.INTERNAL_ERROR;
        ErrorResponse response = new ErrorResponse(
                errorCode.name(),
                errorCode.getCode(),
                errorCode.getMessage(),
                request.getRequestURI()
        );
        return ResponseEntity.status(errorCode.getHttpStatus()).body(response);
    }
}
