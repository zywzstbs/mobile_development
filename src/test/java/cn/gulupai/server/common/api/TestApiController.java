package cn.gulupai.server.common.api;

import cn.gulupai.server.common.validation.DecimalString;
import cn.gulupai.server.common.validation.DecimalStrings;
import cn.gulupai.server.common.validation.OpaqueId;
import cn.gulupai.server.common.validation.Utf16Length;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 仅测试使用的协议探针，不进入生产组件扫描（位于 src/test）。
 */
@RestController
@RequestMapping("/api/v1/test")
public class TestApiController {

    @GetMapping("/ok")
    public ApiResponse<OkData> ok() {
        return ApiResponse.ok(new OkData(DecimalStrings.canonical("12.3"),
                Instant.parse("2026-10-31T06:15:11.987Z"), "咕噜派"));
    }

    @PostMapping("/echo")
    public ApiResponse<EchoData> echo(@Valid @RequestBody EchoRequest request) {
        return ApiResponse.ok(new EchoData(request.text(), request.id(),
                DecimalStrings.canonical(request.points()), request.at(), request.count()));
    }

    @GetMapping("/large-response")
    public ApiResponse<LargeData> largeResponse() {
        return ApiResponse.ok(new LargeData("x".repeat(2 * 1024 * 1024 + 100)));
    }

    @GetMapping("/large-collection")
    public ApiResponse<ListEnvelope<LargeData>> largeCollection() {
        List<LargeData> items = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            items.add(new LargeData("x".repeat(95_000) + i));
        }
        return ApiResponse.ok(ListEnvelope.full(items));
    }

    @GetMapping("/boom")
    public ApiResponse<Void> boom() {
        throw new IllegalStateException("test-only failure");
    }

    public record OkData(String points, Instant at, String text) {
    }

    public record LargeData(String value) {
    }

    public record EchoRequest(
            @NotBlank @Utf16Length(max = 5) String text,
            @OpaqueId String id,
            @DecimalString(min = "0", max = "1000000") String points,
            Instant at,
            Integer count
    ) {
    }

    public record EchoData(String text, String id, String points, Instant at, Integer count) {
    }
}
