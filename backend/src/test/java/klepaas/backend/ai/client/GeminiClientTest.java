package klepaas.backend.ai.client;

import klepaas.backend.ai.client.dto.GeminiRequest;
import klepaas.backend.ai.client.dto.GeminiResponse;
import klepaas.backend.ai.exception.AiProcessingException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class GeminiClientTest {

    @Mock private RestClient restClient;
    @Mock private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    @Mock private RestClient.RequestBodySpec requestBodySpec;
    @Mock private RestClient.ResponseSpec responseSpec;

    private GeminiClient geminiClient;

    @BeforeEach
    void setUp() {
        geminiClient = new GeminiClient(restClient, "test-api-key", "gemini-2.0-flash");
    }

    @Test
    @DisplayName("Gemini API 호출 성공")
    void generateSuccess() {
        var expectedResponse = new GeminiResponse(List.of(
                new GeminiResponse.Candidate(
                        new GeminiResponse.Content(
                                List.of(new GeminiResponse.Part("{\"intent\":\"HELP\"}")),
                                "model"
                        )
                )
        ));

        given(restClient.post()).willReturn(requestBodyUriSpec);
        given(requestBodyUriSpec.uri(anyString())).willReturn(requestBodySpec);
        given(requestBodySpec.header(eq("x-goog-api-key"), any())).willReturn(requestBodySpec);
        given(requestBodySpec.body(any(GeminiRequest.class))).willReturn(requestBodySpec);
        given(requestBodySpec.retrieve()).willReturn(responseSpec);
        given(responseSpec.onStatus(any(), any())).willReturn(responseSpec);
        given(responseSpec.body(GeminiResponse.class)).willReturn(expectedResponse);

        GeminiRequest request = GeminiRequest.of("system prompt", "도움말");
        GeminiResponse response = geminiClient.generate(request);

        assertThat(response.extractText()).contains("HELP");
    }

    @Test
    @DisplayName("GeminiResponse.extractText - 빈 응답")
    void extractTextEmptyResponse() {
        var emptyResponse = new GeminiResponse(List.of());
        assertThat(emptyResponse.extractText()).isEmpty();

        var nullResponse = new GeminiResponse(null);
        assertThat(nullResponse.extractText()).isEmpty();
    }
}
