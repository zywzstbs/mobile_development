package cn.gulupai.server.common.config;

import cn.gulupai.server.common.api.ApiJacksonHttpMessageConverter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * MVC 公共配置：用带大小守卫的 JSON 转换器替换默认 Jackson 转换器。
 */
@Configuration
@EnableConfigurationProperties(ApiProperties.class)
public class WebConfig implements WebMvcConfigurer {

    private final ObjectMapper objectMapper;
    private final ApiProperties apiProperties;

    public WebConfig(ObjectMapper objectMapper, ApiProperties apiProperties) {
        this.objectMapper = objectMapper;
        this.apiProperties = apiProperties;
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.removeIf(MappingJackson2HttpMessageConverter.class::isInstance);
        converters.add(0, new ApiJacksonHttpMessageConverter(objectMapper, apiProperties));
    }
}
