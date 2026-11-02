package cn.gulupai.server.common.api;

import cn.gulupai.server.common.clock.ClockConfig;
import cn.gulupai.server.common.config.JacksonConfig;
import cn.gulupai.server.common.security.RestAccessDeniedHandler;
import cn.gulupai.server.common.security.RestAuthenticationEntryPoint;
import cn.gulupai.server.common.security.SecurityConfig;
import cn.gulupai.server.identity.persistence.SessionRepository;
import cn.gulupai.server.identity.persistence.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TestApiController.class)
@Import({JacksonConfig.class, ClockConfig.class, SecurityConfig.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class})
class ApiContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SessionRepository sessionRepository;

    @MockitoBean
    private UserRepository userRepository;

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.with(user("tester"));
    }

    private static MockHttpServletRequestBuilder jsonPost(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String echoBody(String text, String id, String points) {
        return "{\"text\":\"" + text + "\",\"id\":\"" + id + "\",\"points\":\"" + points + "\"}";
    }

    @Test
    void successEnvelopeUsesUtcSecondsAndStringDecimal() throws Exception {
        mockMvc.perform(authed(get("/api/v1/test/ok")))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("成功"))
                .andExpect(jsonPath("$.data.points").value("12.30"))
                .andExpect(jsonPath("$.data.at").value("2026-10-31T06:15:11Z"));
    }

    @Test
    void creationAlsoUsesHttp200() throws Exception {
        mockMvc.perform(authed(jsonPost("/api/v1/test/echo", echoBody("ok", "abc-123", "1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));
    }

    @Test
    void requestIdIsEchoedOrGenerated() throws Exception {
        mockMvc.perform(authed(get("/api/v1/test/ok")).header("X-Request-Id", "req-001_test"))
                .andExpect(header().string("X-Request-Id", "req-001_test"));

        mockMvc.perform(authed(get("/api/v1/test/ok")))
                .andExpect(header().exists("X-Request-Id"));
    }

    @Test
    void missingAuthenticationReturnsJson401() throws Exception {
        mockMvc.perform(get("/api/v1/test/ok"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void unknownFieldReturns422FieldNotAllowed() throws Exception {
        String body = "{\"text\":\"ok\",\"id\":\"abc\",\"points\":\"1\",\"extra\":true}";
        mockMvc.perform(authed(jsonPost("/api/v1/test/echo", body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FIELD_NOT_ALLOWED"));
    }

    @Test
    void wrongTypeReturns422ValidationFailed() throws Exception {
        String body = "{\"text\":\"ok\",\"id\":123,\"points\":\"1\"}";
        mockMvc.perform(authed(jsonPost("/api/v1/test/echo", body)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void integerScalarsAreNotCoercedFromStringDecimalOrBoolean() throws Exception {
        for (String count : new String[]{"\"1\"", "1.5", "true"}) {
            String body = "{\"text\":\"ok\",\"id\":\"abc\",\"points\":\"1\",\"count\":" + count + "}";
            mockMvc.perform(authed(jsonPost("/api/v1/test/echo", body)))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        String valid = "{\"text\":\"ok\",\"id\":\"abc\",\"points\":\"1\",\"count\":2}";
        mockMvc.perform(authed(jsonPost("/api/v1/test/echo", valid)))
                .andExpect(status().isOk());
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        mockMvc.perform(authed(jsonPost("/api/v1/test/echo", "{\"text\":")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    void emptyBodyReturns400() throws Exception {
        mockMvc.perform(authed(post("/api/v1/test/echo").contentType(MediaType.APPLICATION_JSON)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    void utf16LengthCountsProxyPairs() throws Exception {
        // 5 个 UTF-16 code unit：4 个汉字 + 1 个 ASCII；2 个 emoji（各 2 单元）+ 1 个 ASCII。
        mockMvc.perform(authed(jsonPost("/api/v1/test/echo", echoBody("汉字汉字a", "abc", "1"))))
                .andExpect(status().isOk());
        mockMvc.perform(authed(jsonPost("/api/v1/test/echo", echoBody("😀😀a", "abc", "1"))))
                .andExpect(status().isOk());

        mockMvc.perform(authed(jsonPost("/api/v1/test/echo", echoBody("汉字汉字ab", "abc", "1"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(authed(jsonPost("/api/v1/test/echo", echoBody("😀😀ab", "abc", "1"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void decimalStringsAcceptCanonicalBounds() throws Exception {
        for (String points : new String[]{"0", "999999.99", "1000000", "12.3"}) {
            mockMvc.perform(authed(jsonPost("/api/v1/test/echo", echoBody("ok", "abc", points))))
                    .andExpect(status().isOk());
        }
    }

    @Test
    void decimalStringsRejectNonCanonicalOrOutOfRange() throws Exception {
        for (String points : new String[]{"1.234", "01", "1e2", "-1", "1000000.01", ".5", "1."}) {
            mockMvc.perform(authed(jsonPost("/api/v1/test/echo", echoBody("ok", "abc", points))))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    void invalidOpaqueIdIsRejected() throws Exception {
        for (String id : new String[]{"a b", "a/b", "a?".repeat(60), ""}) {
            String body = echoBody("ok", id, "1");
            mockMvc.perform(authed(jsonPost("/api/v1/test/echo", body)))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    @Test
    void requestBodyOverOneMiBReturns413() throws Exception {
        String body = "{\"text\":\"" + "x".repeat(1024 * 1024) + "\",\"id\":\"abc\",\"points\":\"1\"}";
        mockMvc.perform(authed(jsonPost("/api/v1/test/echo", body)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void responseOverTwoMiBReturns413() throws Exception {
        mockMvc.perform(authed(get("/api/v1/test/large-response")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void collectionOverOnePointSevenFiveMiBReturns413() throws Exception {
        mockMvc.perform(authed(get("/api/v1/test/large-collection")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("COLLECTION_TOO_LARGE"));
    }

    @Test
    void unknownRouteReturns404Json() throws Exception {
        mockMvc.perform(authed(get("/api/v1/test/no-such-route")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void unhandledExceptionReturns500WithoutDetails() throws Exception {
        mockMvc.perform(authed(get("/api/v1/test/boom")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("SERVER_ERROR"))
                .andExpect(jsonPath("$.message").value(containsString("服务器")));
    }

    @Test
    void methodNotAllowedUsesEnvelope() throws Exception {
        mockMvc.perform(authed(post("/api/v1/test/ok").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
