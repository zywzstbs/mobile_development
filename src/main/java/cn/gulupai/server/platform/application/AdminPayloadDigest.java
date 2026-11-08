package cn.gulupai.server.platform.application;

import cn.gulupai.server.common.idempotency.CanonicalJson;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理操作摘要。键由 CanonicalJson 排序；reason/note/decision/status 先 trim，空白记为 null。
 * 不包含 password、reauthToken、payloadDigest 或 operationId。整数用 JSON 数字，缺省为 null。
 * 非 ASCII 按 UTF-8 原样写入，不转成 \\uXXXX。
 */
@Component
public class AdminPayloadDigest {

    private final CanonicalJson canonicalJson;

    public AdminPayloadDigest(CanonicalJson canonicalJson) {
        this.canonicalJson = canonicalJson;
    }

    public String digest(String action, String targetType, String targetId, Integer expectedRevision,
                         String precondition, String decision, String status, String reason, String note,
                         String targetUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("decision", AdminTexts.blankToNull(decision));
        body.put("expectedRevision", expectedRevision);
        body.put("note", AdminTexts.blankToNull(note));
        body.put("precondition", AdminTexts.blankToNull(precondition));
        body.put("reason", AdminTexts.blankToNull(reason));
        body.put("status", AdminTexts.blankToNull(status));
        body.put("targetId", targetId);
        body.put("targetType", targetType);
        body.put("targetUserId", AdminTexts.blankToNull(targetUserId));
        return canonicalJson.hash(body);
    }
}
