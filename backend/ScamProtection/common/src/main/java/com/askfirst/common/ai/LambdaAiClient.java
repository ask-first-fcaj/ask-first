package com.askfirst.common.ai;

import com.askfirst.common.ai.AiContract.AiError;
import com.askfirst.common.ai.AiContract.AiResponse;
import com.askfirst.common.ai.AiContract.AnalyzeRequest;
import com.askfirst.common.ai.AiContract.AnalyzeResult;
import com.askfirst.common.ai.AiContract.OutMessage;
import com.askfirst.common.ai.AiContract.RenderGuardianAlertsRequest;
import com.askfirst.common.ai.AiContract.RenderRequest;
import com.askfirst.common.ai.AiContract.ReplyPlan;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link AiClient} qua {@link LambdaInvoker}. Quy tắc lỗi (plan §3.2 bước 8):
 * <ul>
 *   <li>invoke lỗi / FunctionError / body không parse được: retryable, code "invoke".</li>
 *   <li>{@code error.kind = retryable}: retryable với code do ai trả; {@code fatal}: fatal.</li>
 *   <li>Vi phạm contract (sai major version, sai op, thiếu kết quả, sai số alert): fatal "contract",
 *       vì retry sẽ cho đúng kết quả đó.</li>
 * </ul>
 * Log chỉ gồm op, trace_id và thời gian; không log request/response.
 */
public final class LambdaAiClient implements AiClient {

    private final LambdaInvoker invoker;
    private final Consumer<String> log;

    public LambdaAiClient(LambdaInvoker invoker, Consumer<String> log) {
        this.invoker = Objects.requireNonNull(invoker, "invoker");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public AnalyzeResult analyze(AnalyzeRequest request) {
        AiResponse response = call(AiContract.OP_ANALYZE, request, request.ctx().checkId());
        if (response.analyze() == null || response.analyze().verdict() == null) {
            throw AiException.fatal("contract");
        }
        return response.analyze();
    }

    @Override
    public List<OutMessage> renderGuardianAlerts(RenderGuardianAlertsRequest request) {
        AiResponse response = call(AiContract.OP_RENDER_GUARDIAN_ALERTS, request, "-");
        if (response.renderGuardianAlerts() == null) {
            throw AiException.fatal("contract");
        }
        List<OutMessage> alerts = response.renderGuardianAlerts().alerts();
        if (alerts.size() != request.guardians().size()) {
            throw AiException.fatal("contract");
        }
        return alerts;
    }

    @Override
    public ReplyPlan render(RenderRequest request) {
        AiResponse response = call(AiContract.OP_RENDER, request, request.ctx().checkId());
        if (response.render() == null || response.render().messages().isEmpty()) {
            throw AiException.fatal("contract");
        }
        return response.render();
    }

    private AiResponse call(String op, Object request, String traceId) {
        String body = AiCodec.toJson(request);
        long start = System.nanoTime();
        String outcome = "ok";
        try {
            String raw = invoke(body);
            AiResponse response = AiCodec.parseResponse(raw);
            if (response.v() == null || !response.v().startsWith("1.")) {
                throw AiException.fatal("contract");
            }
            AiError error = response.error();
            if (error != null) {
                outcome = "ai_error";
                boolean retryable = "retryable".equals(error.kind());
                String code = error.code() == null || error.code().isBlank() ? "unknown" : error.code();
                throw retryable ? AiException.retryable(code) : AiException.fatal(code);
            }
            if (!op.equals(response.op())) {
                throw AiException.fatal("contract");
            }
            return response;
        } catch (AiException e) {
            if ("ok".equals(outcome)) {
                outcome = e.code();
            }
            throw e;
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            log.accept("ai_invoke op=" + op + " trace=" + traceId + " ms=" + ms + " outcome=" + outcome);
        }
    }

    private String invoke(String body) {
        try {
            return invoker.invoke(body);
        } catch (LambdaInvokeException e) {
            throw AiException.retryable("invoke");
        }
    }
}
