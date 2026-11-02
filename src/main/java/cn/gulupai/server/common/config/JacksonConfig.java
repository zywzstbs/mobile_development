package cn.gulupai.server.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * JSON 协议基线：
 * <ul>
 *   <li>未知字段拒绝；不做字符串/小数/布尔到整数的隐式转换；</li>
 *   <li>时间统一 {@code yyyy-MM-dd'T'HH:mm:ss'Z'}（UTC，秒精度）；</li>
 *   <li>数字/点数仍以字符串承载，不使用 Jackson 浮点转换。</li>
 * </ul>
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter UTC_SECONDS =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer protocolJacksonCustomizer() {
        return builder -> {
            builder.timeZone(TimeZone.getTimeZone("UTC"));
            builder.featuresToDisable(
                    SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                    MapperFeature.ALLOW_COERCION_OF_SCALARS,
                    DeserializationFeature.ACCEPT_FLOAT_AS_INT,
                    DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL);
            builder.featuresToEnable(
                    DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            SimpleModule module = new SimpleModule("gulupai-protocol");
            module.addSerializer(Instant.class, new UtcSecondInstantSerializer());
            module.addDeserializer(String.class, new StrictStringDeserializer());
            builder.modulesToInstall(module);
        };
    }

    /**
     * 不输出小数秒；输入仍由 Jackson 标准 Instant 反序列化接受合法偏移。
     */
    static final class UtcSecondInstantSerializer extends JsonSerializer<Instant> {
        @Override
        public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
            gen.writeString(UTC_SECONDS.format(value));
        }
    }

    /**
     * 字符串字段只接受 JSON string；禁止数字/布尔值被 Jackson 静默转成 String。
     */
    static final class StrictStringDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return context.reportInputMismatch(String.class, "字段必须是字符串");
            }
            return parser.getText();
        }
    }
}
