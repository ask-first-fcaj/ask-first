package com.askfirst.common.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.askfirst.common.ai.AiContract.AnalyzeContext;
import com.askfirst.common.ai.AiContract.AnalyzeRequest;
import com.askfirst.common.ai.AiContract.Flags;
import com.askfirst.common.ai.AiContract.GuardianAlertContext;
import com.askfirst.common.ai.AiContract.RenderContext;
import com.askfirst.common.ai.AiContract.RenderGuardianAlertsRequest;
import com.askfirst.common.ai.AiContract.RenderRequest;
import com.askfirst.common.ai.AiContract.Verdict;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LambdaAiClientTest {

    private static final String ID = "01J00000000000000000000000";

    private final List<String> logs = new ArrayList<>();

    private static JsonNode fixture(String op, String name) {
        return ContractCompat.load(op).get(name);
    }

    private static String responseOf(String op, String name) {
        return fixture(op, name).get("response").toString();
    }

    private static AnalyzeRequest analyzeRequest() {
        return AnalyzeRequest.of(List.of(), null,
                new AnalyzeContext(ID, "tg:42", Flags.defaults(), 85.0));
    }

    private static Verdict otpVerdict() {
        return AiCodec.fromTree(fixture("analyze", "otp.json").get("response"), AiContract.AiResponse.class)
                .analyze().verdict();
    }

    private LambdaAiClient client(LambdaInvoker invoker) {
        return new LambdaAiClient(invoker, logs::add);
    }

    @Test
    void analyze_success_sendsContractJsonAndReturnsVerdict() {
        List<String> sent = new ArrayList<>();
        LambdaAiClient client = client(payload -> {
            sent.add(payload);
            return responseOf("analyze", "otp.json");
        });

        var result = client.analyze(analyzeRequest());

        assertThat(result.verdict().shouldAlert()).isTrue();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0)).contains("\"op\":\"analyze\"").contains("\"v\":\"1.0\"")
                .contains("\"check_id\":\"" + ID + "\"");
    }

    @Test
    void analyze_logsOnlyOpTraceAndTiming() {
        client(p -> responseOf("analyze", "otp.json")).analyze(analyzeRequest());

        assertThat(logs).hasSize(1);
        assertThat(logs.get(0)).startsWith("ai_invoke op=analyze trace=" + ID).contains("outcome=ok");
        assertThat(logs.get(0)).doesNotContain("OTP");
    }

    @Test
    void analyze_functionError_isRetryableInvoke() {
        LambdaAiClient client = client(p -> {
            throw new LambdaInvokeException("function_error");
        });

        assertThatThrownBy(() -> client.analyze(analyzeRequest()))
                .isInstanceOfSatisfying(AiException.class, e -> {
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.code()).isEqualTo("invoke");
                });
        assertThat(logs.get(0)).contains("outcome=invoke");
    }

    @Test
    void analyze_garbageBody_isRetryableAndDoesNotLeakBody() {
        String secret = "NOI-DUNG-NHAY-CAM-OTP-123456";
        LambdaAiClient client = client(p -> "{not json " + secret);

        assertThatThrownBy(() -> client.analyze(analyzeRequest()))
                .isInstanceOfSatisfying(AiException.class, e -> {
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.getMessage()).doesNotContain(secret);
                    assertThat(e.getCause()).isNull();
                });
        assertThat(String.join("", logs)).doesNotContain(secret);
    }

    @Test
    void analyze_aiErrorRetryable_andFatal() {
        String retryable = "{\"v\":\"1.0\",\"op\":\"analyze\",\"error\":{\"kind\":\"retryable\",\"code\":\"deadline\"}}";
        String fatal = "{\"v\":\"1.0\",\"op\":\"analyze\",\"error\":{\"kind\":\"fatal\",\"code\":\"bad_request\"}}";

        assertThatThrownBy(() -> client(p -> retryable).analyze(analyzeRequest()))
                .isInstanceOfSatisfying(AiException.class, e -> {
                    assertThat(e.isRetryable()).isTrue();
                    assertThat(e.code()).isEqualTo("deadline");
                });
        assertThatThrownBy(() -> client(p -> fatal).analyze(analyzeRequest()))
                .isInstanceOfSatisfying(AiException.class, e -> {
                    assertThat(e.isRetryable()).isFalse();
                    assertThat(e.code()).isEqualTo("bad_request");
                });
    }

    @Test
    void analyze_contractViolations_areFatal() {
        String wrongMajor = "{\"v\":\"2.0\",\"op\":\"analyze\"}";
        String wrongOp = responseOf("render", "default.json");
        String noResult = "{\"v\":\"1.0\",\"op\":\"analyze\"}";

        for (String body : List.of(wrongMajor, wrongOp, noResult)) {
            assertThatThrownBy(() -> client(p -> body).analyze(analyzeRequest()))
                    .isInstanceOfSatisfying(AiException.class, e -> {
                        assertThat(e.isRetryable()).isFalse();
                        assertThat(e.code()).isEqualTo("contract");
                    });
        }
    }

    @Test
    void render_success_returnsReplyPlan() {
        RenderRequest request = RenderRequest.of(otpVerdict(),
                new RenderContext(ID, "elder", true, List.of(), false));

        var plan = client(p -> responseOf("render", "otp.json")).render(request);

        assertThat(plan.messages()).hasSize(1);
    }

    @Test
    void renderGuardianAlerts_returnsAlertsInOrder_andRejectsCountMismatch() {
        GuardianAlertContext one = new GuardianAlertContext(ID, "Lan", 2, Instant.parse("2026-10-14T07:05:00Z"));
        RenderGuardianAlertsRequest ok = RenderGuardianAlertsRequest.of(otpVerdict(), List.of(one));
        RenderGuardianAlertsRequest two = RenderGuardianAlertsRequest.of(otpVerdict(), List.of(one, one));
        LambdaAiClient client = client(p -> responseOf("render_guardian_alerts", "otp.json"));

        assertThat(client.renderGuardianAlerts(ok)).hasSize(1);
        assertThatThrownBy(() -> client.renderGuardianAlerts(two))
                .isInstanceOfSatisfying(AiException.class, e -> assertThat(e.isRetryable()).isFalse());
    }

    @Test
    void renderGuardianAlertsRequest_rejectsEmptyGuardians() {
        assertThatThrownBy(() -> RenderGuardianAlertsRequest.of(otpVerdict(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
