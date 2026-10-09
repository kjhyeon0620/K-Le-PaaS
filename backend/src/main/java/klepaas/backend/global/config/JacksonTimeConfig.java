package klepaas.backend.global.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * API 응답의 시각 형식 (#81). 시각은 UTC로 저장한다(애플리케이션 시간대를 UTC로 고정, {@code BackendApplication}).
 * {@code LocalDateTime}에는 시간대가 없으므로 응답에서 UTC로 해석해 {@code Z}를 붙인다. 표시 시간대는 클라이언트가 정한다.
 */
@Configuration
public class JacksonTimeConfig {

    @Bean
    JsonMapperBuilderCustomizer utcLocalDateTimeSerializer() {
        SimpleModule module = new SimpleModule().addSerializer(LocalDateTime.class, new ValueSerializer<>() {
            @Override
            public void serialize(LocalDateTime value, JsonGenerator generator, SerializationContext context) {
                generator.writeString(value.toInstant(ZoneOffset.UTC).toString());
            }
        });
        return builder -> builder.addModule(module);
    }
}
