package cn.gulupai.server.governance;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;
import cn.gulupai.server.common.idempotency.IdempotencyCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * 历史材料提交时间的证据验证器。
 *
 * <p>只接受一条仍存在的、由当前密钥加密的成功 POST 提交回执，并要求：
 * 回执路径与作者匹配目标版本；解密后的原始 {@link GovViews.Material} 根节点
 * 指向同一材料；顶层 {@code responseVersion} 精确等于目标版本；该版本在
 * {@code responses} 中恰好出现一次，作者一致、时间为合法 Instant、可信度为
 * {@code EXACT}、未撤回；若兼容对象 {@code response} 存在，必须与数组条目一致。
 *
 * <p>本类不查询数据库、不写数据库，也不输出密文、密钥或正文。它返回不可变的
 * {@link VerifiedSubmissionEvidence}，避免恢复服务把回执创建时间或调用方参数
 * 当作提交时间。
 */
@Service
public class MaterialSubmissionEvidenceVerifier {

    static final String EXACT = "EXACT";

    private final IdempotencyCipher cipher;
    private final ObjectMapper objectMapper;

    public MaterialSubmissionEvidenceVerifier(IdempotencyCipher cipher, ObjectMapper objectMapper) {
        this.cipher = cipher;
        this.objectMapper = objectMapper;
    }

    public VerifiedSubmissionEvidence verify(GovRepository.MaterialResponseRow target,
                                             GovRepository.OperationReceiptEvidence receipt) {
        verifyTarget(target);
        verifyOuter(target, receipt);

        JsonNode root = parse(decrypt(receipt.resultCiphertext()));
        if (root == null || !root.isObject()) {
            throw sourceChanged("回执结果不是对象，不能证明材料提交");
        }
        if (!target.materialId().equals(text(root, "id", "回执结果缺少材料标识"))) {
            throw sourceChanged("回执结果不属于目标材料");
        }
        int producedVersion = integer(root, "responseVersion", "回执结果缺少本次提交版本");
        if (producedVersion != target.version()) {
            throw sourceChanged("回执结果产生的版本与目标材料版本不一致");
        }

        JsonNode responses = root.get("responses");
        if (responses == null || !responses.isArray()) {
            throw sourceChanged("回执结果缺少可验证的响应历史");
        }
        JsonNode targetItem = null;
        int matches = 0;
        for (JsonNode item : responses) {
            if (!item.isObject()) {
                throw sourceChanged("回执响应历史格式无效");
            }
            int itemVersion = integer(item, "version", "回执响应历史格式无效");
            if (itemVersion == target.version()) {
                matches++;
                targetItem = item;
            }
        }
        if (matches == 0) {
            throw sourceChanged("回执结果不包含目标材料版本");
        }
        if (matches > 1) {
            throw sourceChanged("回执结果包含重复的目标材料版本");
        }
        Instant submittedAt = verifyTargetItem(target, targetItem);

        JsonNode compatible = root.get("response");
        if (compatible != null && !compatible.isNull()) {
            verifyCompatibleObject(target, submittedAt, compatible);
        }
        return new VerifiedSubmissionEvidence(target.materialId(), target.version(), target.authorId(),
                submittedAt, receipt.id());
    }

    private void verifyTarget(GovRepository.MaterialResponseRow target) {
        if (target == null || target.materialId() == null || target.materialId().isBlank()
                || target.version() < 1) {
            throw sourceChanged("目标材料版本无效，不能恢复时间");
        }
        if (target.authorId() == null || target.authorId().isBlank()) {
            throw sourceChanged("目标材料版本作者缺失，不能恢复时间");
        }
    }

    private void verifyOuter(GovRepository.MaterialResponseRow target,
                             GovRepository.OperationReceiptEvidence receipt) {
        if (receipt == null) {
            throw sourceChanged("缺少提交回执，不能恢复时间");
        }
        if (!"POST".equals(receipt.method())) {
            throw sourceChanged("回执不是材料提交动作");
        }
        String expectedPath = "/api/v1/material-requests/" + target.materialId() + "/responses";
        if (!expectedPath.equals(receipt.path())) {
            throw sourceChanged("回执路径与目标材料提交动作不一致");
        }
        if (receipt.httpStatus() != 200 || !"OK".equals(receipt.businessCode())) {
            throw sourceChanged("回执不是成功的材料提交结果");
        }
        if (!target.authorId().equals(receipt.userId())) {
            throw sourceChanged("回执作者与目标材料版本作者不一致");
        }
        if (receipt.resultCiphertext() == null || receipt.resultCiphertext().isBlank()) {
            throw sourceChanged("回执缺少加密结果");
        }
        if (receipt.keyId() == null || !receipt.keyId().equals(cipher.keyId())) {
            throw sourceChanged("回执加密版本不受支持，不能恢复时间");
        }
    }

    private String decrypt(String ciphertext) {
        try {
            return cipher.decrypt(ciphertext);
        } catch (RuntimeException ex) {
            // 不把密文、密钥或底层异常细节带给调用方。
            throw sourceChanged("回执加密结果无法解密或完整性校验失败");
        }
    }

    private JsonNode parse(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null) {
                throw sourceChanged("回执加密结果为空");
            }
            return node;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw sourceChanged("回执加密结果不是有效JSON");
        }
    }

    private Instant verifyTargetItem(GovRepository.MaterialResponseRow target, JsonNode item) {
        if (!target.authorId().equals(text(item, "authorId", "目标版本缺少作者"))) {
            throw sourceChanged("回执中目标版本作者与材料记录不一致");
        }
        String confidence = text(item, "createdAtConfidence", "目标版本缺少提交时间可信度");
        if (!EXACT.equals(confidence)) {
            throw sourceChanged("目标版本提交时间可信度不是 EXACT");
        }
        Instant submittedAt = instant(item, "createdAt", "目标版本缺少合法提交时间");
        JsonNode withdrawn = item.get("withdrawn");
        if (withdrawn == null || !withdrawn.isBoolean() || withdrawn.booleanValue()) {
            throw sourceChanged("目标版本不是未撤回的新提交结果");
        }
        return submittedAt;
    }

    private void verifyCompatibleObject(GovRepository.MaterialResponseRow target, Instant submittedAt,
                                        JsonNode compatible) {
        if (!compatible.isObject()) {
            throw sourceChanged("回执兼容响应对象格式无效");
        }
        int version = integer(compatible, "version", "回执兼容响应对象缺少版本");
        if (version != target.version()) {
            throw sourceChanged("回执兼容响应对象版本与目标版本不一致");
        }
        if (!target.authorId().equals(text(compatible, "authorId", "回执兼容响应对象缺少作者"))) {
            throw sourceChanged("回执兼容响应对象作者与目标版本不一致");
        }
        if (!EXACT.equals(text(compatible, "createdAtConfidence",
                "回执兼容响应对象缺少可信度"))) {
            throw sourceChanged("回执兼容响应对象可信度与目标版本不一致");
        }
        Instant compatibleAt = instant(compatible, "createdAt", "回执兼容响应对象缺少合法时间");
        if (!submittedAt.equals(compatibleAt)) {
            throw sourceChanged("回执兼容响应对象时间与目标版本不一致");
        }
        JsonNode withdrawn = compatible.get("withdrawn");
        if (withdrawn == null || !withdrawn.isBoolean() || withdrawn.booleanValue()) {
            throw sourceChanged("回执兼容响应对象与目标版本的撤回状态不一致");
        }
    }

    private static String text(JsonNode node, String field, String failure) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw sourceChanged(failure);
        }
        return value.asText();
    }

    private static int integer(JsonNode node, String field, String failure) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw sourceChanged(failure);
        }
        return value.intValue();
    }

    private static Instant instant(JsonNode node, String field, String failure) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw sourceChanged(failure);
        }
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException ex) {
            throw sourceChanged(failure);
        }
    }

    private static ApiException sourceChanged(String message) {
        return new ApiException(ApiCode.SOURCE_CHANGED, message);
    }
}
