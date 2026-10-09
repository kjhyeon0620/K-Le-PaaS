package klepaas.backend.global.config;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonTimeConfigTest {

    @Test
    void localDateTimeIsWrittenAsUtcWithZone() {
        JsonMapper.Builder builder = JsonMapper.builder();
        new JacksonTimeConfig().utcLocalDateTimeSerializer().customize(builder);
        JsonMapper mapper = builder.build();

        assertThat(mapper.writeValueAsString(LocalDateTime.of(2026, 10, 9, 9, 9, 30, 682_000_000)))
                .isEqualTo("\"2026-10-09T09:09:30.682Z\"");
        assertThat(mapper.writeValueAsString(LocalDateTime.of(2026, 10, 9, 9, 9)))
                .isEqualTo("\"2026-10-09T09:09:00Z\"");
    }
}
