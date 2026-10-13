package klepaas.backend.global.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String error,
        String code,
        String message,
        String path,
        String installUrl
) {
    public ErrorResponse(String error, String code, String message, String path) {
        this(error, code, message, path, null);
    }
}
