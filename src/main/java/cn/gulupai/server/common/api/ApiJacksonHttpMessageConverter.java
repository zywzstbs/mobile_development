package cn.gulupai.server.common.api;

import cn.gulupai.server.common.config.ApiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.io.IOException;
import java.lang.reflect.Type;

/**
 * 在 JSON 写出前完成大小计数：
 * 完整集合超过 1.75MiB 返回 COLLECTION_TOO_LARGE，其他响应超过 2MiB 返回 PAYLOAD_TOO_LARGE。
 *
 * <p>先序列化到内存再写出，避免已经写出半截 JSON 才发现超限；文件字节端点不使用该转换器。
 */
public class ApiJacksonHttpMessageConverter extends MappingJackson2HttpMessageConverter {

    private final long maxResponseBytes;
    private final long maxCollectionBytes;

    public ApiJacksonHttpMessageConverter(ObjectMapper objectMapper, ApiProperties properties) {
        super(objectMapper);
        this.maxResponseBytes = properties.getMaxResponseBytes();
        this.maxCollectionBytes = properties.getMaxCollectionBytes();
    }

    @Override
    protected void writeInternal(Object object, Type type, HttpOutputMessage outputMessage) throws IOException {
        byte[] bytes = getObjectMapper().writeValueAsBytes(object);
        boolean collection = object instanceof ListEnvelope<?>
                || (object instanceof ApiResponse<?> response && response.data() instanceof ListEnvelope<?>);
        if (collection && bytes.length > maxCollectionBytes) {
            throw new CollectionTooLargeException();
        }
        if (bytes.length > maxResponseBytes) {
            throw new ResponseTooLargeException();
        }
        HttpHeaders headers = outputMessage.getHeaders();
        headers.setContentLength(bytes.length);
        outputMessage.getBody().write(bytes);
        outputMessage.getBody().flush();
    }
}
