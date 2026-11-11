package com.askfirst.common.ai;

import com.askfirst.common.channel.model.InboundMessage;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Contract Java <-> Python (Lambda {@code ai}), viết tay theo {@code ai/src/askfirst/domain/models.py}
 * và {@code contracts/schema/*.json}. Nguồn sự thật là models.py: sửa ở đó thì phải sửa ở đây.
 * {@code AiContractTest} đối chiếu các record này với {@code contracts/fixtures/**} để bắt lệch.
 *
 * <p>Python dùng {@code extra="forbid"} nên mọi request phải chỉ chứa đúng field của schema:
 * record chỉ khai báo field của schema và bỏ qua giá trị null ({@code NON_NULL}).
 * {@code InboundMessage} dùng lại record có sẵn trong {@code channel.model}.
 *
 * <p>Verdict chứa {@code masked_text}, {@code explanation}, {@code Signal.evidence}: KHÔNG được log.
 */
public final class AiContract {

    public static final String VERSION = "1.0";
    public static final String OP_ANALYZE = "analyze";
    public static final String OP_RENDER_GUARDIAN_ALERTS = "render_guardian_alerts";
    public static final String OP_RENDER = "render";

    private AiContract() {
    }

    private static <T> List<T> listOrEmpty(List<T> in) {
        return in == null ? List.of() : List.copyOf(in);
    }

    // ---------- Input (Java -> ai) ----------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Flags(
            @JsonProperty("llm_enabled") boolean llmEnabled,
            @JsonProperty("feature_image") boolean featureImage,
            @JsonProperty("feature_voice") boolean featureVoice,
            @JsonProperty("feature_rag") boolean featureRag) {

        /** Giá trị mặc định của models.py (cả 4 cờ bật). */
        public static Flags defaults() {
            return new Flags(true, true, true, true);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnalyzeContext(
            @JsonProperty("check_id") String checkId,
            @JsonProperty("user_id") String userId,
            @JsonProperty("flags") Flags flags,
            @JsonProperty("deadline_s") double deadlineS) {

        public AnalyzeContext {
            Objects.requireNonNull(checkId, "check_id");
            Objects.requireNonNull(userId, "user_id");
            Objects.requireNonNull(flags, "flags");
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SessionContext(
            @JsonProperty("prev_check_id") String prevCheckId,
            @JsonProperty("masked_text") String maskedText,
            @JsonProperty("question") String question,
            @JsonProperty("answer") String answer,       // yes | no | unknown
            @JsonProperty("turn") int turn) {             // 1..2
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnalyzeRequest(
            @JsonProperty("v") String v,
            @JsonProperty("op") String op,
            @JsonProperty("items") List<InboundMessage> items,
            @JsonProperty("session") SessionContext session,
            @JsonProperty("ctx") AnalyzeContext ctx) {

        public AnalyzeRequest {
            items = listOrEmpty(items);
            Objects.requireNonNull(ctx, "ctx");
        }

        public static AnalyzeRequest of(List<InboundMessage> items, SessionContext session, AnalyzeContext ctx) {
            return new AnalyzeRequest(VERSION, OP_ANALYZE, items, session, ctx);
        }
    }

    // ---------- Verdict (ai -> Java, và Java gửi lại cho render) ----------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Signal(
            @JsonProperty("code") String code,
            @JsonProperty("weight") int weight,
            @JsonProperty("hard") boolean hard,
            @JsonProperty("evidence") String evidence) {   // KHÔNG log
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Citation(
            @JsonProperty("title") String title,
            @JsonProperty("url") String url,
            @JsonProperty("publisher") String publisher,
            @JsonProperty("date") String date) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Verdict(
            @JsonProperty("outcome") String outcome,                 // verdict | smalltalk | unreadable
            @JsonProperty("level") String level,                     // DANGEROUS | SUSPICIOUS | UNSURE | LOW_RISK
            @JsonProperty("score") int score,
            @JsonProperty("group") String group,
            @JsonProperty("scenario") String scenario,
            @JsonProperty("signals") List<Signal> signals,
            @JsonProperty("risky_actions") List<String> riskyActions,
            @JsonProperty("should_alert") boolean shouldAlert,
            @JsonProperty("explanation") String explanation,         // KHÔNG log
            @JsonProperty("citations") List<Citation> citations,
            @JsonProperty("followup_question") String followupQuestion,
            @JsonProperty("masked_text") String maskedText,          // KHÔNG log
            @JsonProperty("path") String path,                       // cache | fast | llm | escalated | rules_only
            @JsonProperty("model_ids") List<String> modelIds,
            @JsonProperty("versions") Map<String, String> versions,
            @JsonProperty("latency_ms") Map<String, Integer> latencyMs,
            @JsonProperty("cost_usd_est") double costUsdEst) {

        public Verdict {
            signals = listOrEmpty(signals);
            riskyActions = listOrEmpty(riskyActions);
            citations = listOrEmpty(citations);
            modelIds = listOrEmpty(modelIds);
            versions = versions == null ? Map.of() : Map.copyOf(versions);
            latencyMs = latencyMs == null ? Map.of() : Map.copyOf(latencyMs);
            explanation = explanation == null ? "" : explanation;
            maskedText = maskedText == null ? "" : maskedText;
        }
    }

    // ---------- Output của render ----------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Button(
            @JsonProperty("kind") String kind,
            @JsonProperty("label") String label) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OutMessage(
            @JsonProperty("text_html") String textHtml,              // đã escape + defang, gửi nguyên văn parse_mode=HTML
            @JsonProperty("buttons") List<List<Button>> buttons,
            @JsonProperty("silent") boolean silent) {

        public OutMessage {
            buttons = buttons == null ? List.of() : buttons.stream().map(AiContract::listOrEmpty).toList();
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ReplyPlan(@JsonProperty("messages") List<OutMessage> messages) {

        public ReplyPlan {
            messages = listOrEmpty(messages);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AlertResult(
            @JsonProperty("guardian_name") String guardianName,
            @JsonProperty("status") String status) {          // sent | failed (sent chỉ khi Telegram trả message_id)
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RenderContext(
            @JsonProperty("check_id") String checkId,
            @JsonProperty("address_form") String addressForm,   // elder | aunt | uncle
            @JsonProperty("has_guardian") boolean hasGuardian,
            @JsonProperty("alerts") List<AlertResult> alerts,
            @JsonProperty("maintenance") boolean maintenance) {

        public RenderContext {
            Objects.requireNonNull(checkId, "check_id");
            Objects.requireNonNull(addressForm, "address_form");
            alerts = listOrEmpty(alerts);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GuardianAlertContext(
            @JsonProperty("alert_id") String alertId,
            @JsonProperty("elder_name") String elderName,
            @JsonProperty("share_level") int shareLevel,        // 1..3
            @JsonProperty("at") Instant at) {
    }

    // ---------- Wire ----------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RenderGuardianAlertsRequest(
            @JsonProperty("v") String v,
            @JsonProperty("op") String op,
            @JsonProperty("verdict") Verdict verdict,
            @JsonProperty("guardians") List<GuardianAlertContext> guardians) {

        public RenderGuardianAlertsRequest {
            Objects.requireNonNull(verdict, "verdict");
            guardians = listOrEmpty(guardians);
            if (guardians.isEmpty()) {
                throw new IllegalArgumentException("guardians must not be empty");
            }
        }

        public static RenderGuardianAlertsRequest of(Verdict verdict, List<GuardianAlertContext> guardians) {
            return new RenderGuardianAlertsRequest(VERSION, OP_RENDER_GUARDIAN_ALERTS, verdict, guardians);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RenderRequest(
            @JsonProperty("v") String v,
            @JsonProperty("op") String op,
            @JsonProperty("verdict") Verdict verdict,
            @JsonProperty("ctx") RenderContext ctx) {

        public RenderRequest {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(ctx, "ctx");
        }

        public static RenderRequest of(Verdict verdict, RenderContext ctx) {
            return new RenderRequest(VERSION, OP_RENDER, verdict, ctx);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AnalyzeResult(
            @JsonProperty("verdict") Verdict verdict,
            @JsonProperty("cache_hit") boolean cacheHit) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RenderGuardianAlertsResult(@JsonProperty("alerts") List<OutMessage> alerts) {

        public RenderGuardianAlertsResult {
            alerts = listOrEmpty(alerts);
        }
    }

    /** retryable: để SQS retry; fatal: input/bug, trả câu suy giảm, không retry. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AiError(
            @JsonProperty("kind") String kind,
            @JsonProperty("code") String code) {
    }

    /** Mỗi op trả đúng một trong các field kết quả (khớp tên op) hoặc {@code error}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AiResponse(
            @JsonProperty("v") String v,
            @JsonProperty("op") String op,
            @JsonProperty("analyze") AnalyzeResult analyze,
            @JsonProperty("render_guardian_alerts") RenderGuardianAlertsResult renderGuardianAlerts,
            @JsonProperty("render") ReplyPlan render,
            @JsonProperty("error") AiError error) {
    }
}
