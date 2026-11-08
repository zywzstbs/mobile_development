package cn.gulupai.server.platform.application;

import cn.gulupai.server.common.api.ApiCode;
import cn.gulupai.server.common.api.ApiException;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * 把内部分页令牌封成十六进制。结果只含 [0-9a-f]，满足客户端游标白名单，且不含 +/=。
 */
public final class AdminCursorCodec {

    private static final HexFormat HEX = HexFormat.of();

    private AdminCursorCodec() {
    }

    public static String encode(String token) {
        return HEX.formatHex(token.getBytes(StandardCharsets.UTF_8));
    }

    public static String decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return "";
        }
        if (cursor.length() > 300 || cursor.length() % 2 != 0 || !cursor.matches("[0-9a-f]+")) {
            throw new ApiException(ApiCode.CURSOR_EXPIRED);
        }
        String token = new String(HEX.parseHex(cursor), StandardCharsets.UTF_8);
        if (token.isBlank() || token.length() > 200) {
            throw new ApiException(ApiCode.CURSOR_EXPIRED);
        }
        return token;
    }
}
