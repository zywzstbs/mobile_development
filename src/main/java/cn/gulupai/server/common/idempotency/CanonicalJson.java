package cn.gulupai.server.common.idempotency;

import cn.gulupai.server.common.security.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 解析后规范 JSON 摘要：对象键递归排序，数组顺序与字符串原值保持不变。
 */
@Component
public class CanonicalJson {

    private final ObjectMapper objectMapper;

    public CanonicalJson(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String hash(Object body) {
        JsonNode node = body == null
                ? JsonNodeFactory.instance.nullNode()
                : objectMapper.valueToTree(body);
        JsonNode canonical = sort(node);
        try {
            return Hashing.sha256Hex(objectMapper.writeValueAsBytes(canonical));
        } catch (Exception ex) {
            throw new IllegalStateException("无法生成请求正文摘要", ex);
        }
    }

    private JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            Map<String, JsonNode> sorted = new TreeMap<>();
            node.properties().forEach(entry -> sorted.put(entry.getKey(), sort(entry.getValue())));
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            sorted.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            List<JsonNode> items = new ArrayList<>();
            node.forEach(item -> items.add(sort(item)));
            items.forEach(result::add);
            return result;
        }
        return node;
    }
}
