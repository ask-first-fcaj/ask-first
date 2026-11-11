package com.askfirst.common.ai;

import com.askfirst.common.ai.AiContract.AnalyzeRequest;
import com.askfirst.common.ai.AiContract.AnalyzeResult;
import com.askfirst.common.ai.AiContract.OutMessage;
import com.askfirst.common.ai.AiContract.RenderGuardianAlertsRequest;
import com.askfirst.common.ai.AiContract.RenderRequest;
import com.askfirst.common.ai.AiContract.ReplyPlan;
import java.util.List;

/**
 * Cổng duy nhất để worker gọi Lambda {@code ai}. Không retry nội bộ: retry đi qua SQS.
 * Mọi lỗi ra {@link AiException}.
 */
public interface AiClient {

    AnalyzeResult analyze(AnalyzeRequest request);

    /** Trả về đúng số phần tử và cùng thứ tự với {@code request.guardians()}. */
    List<OutMessage> renderGuardianAlerts(RenderGuardianAlertsRequest request);

    ReplyPlan render(RenderRequest request);
}
