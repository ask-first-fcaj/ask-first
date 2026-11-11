package com.askfirst.common.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.askfirst.common.ai.AiContract.AiResponse;
import com.askfirst.common.ai.AiContract.AnalyzeRequest;
import com.askfirst.common.ai.AiContract.RenderGuardianAlertsRequest;
import com.askfirst.common.ai.AiContract.RenderRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Đối chiếu record Java với contracts/fixtures/** (nguồn: ai/models.py). Lệch contract thì test đỏ. */
class AiContractTest {

    @Test
    void analyzeFixtures_requestsAndResponses() {
        check("analyze", AnalyzeRequest.class);
    }

    @Test
    void renderFixtures_requestsAndResponses() {
        check("render", RenderRequest.class);
    }

    @Test
    void renderGuardianAlertsFixtures_requestsAndResponses() {
        check("render_guardian_alerts", RenderGuardianAlertsRequest.class);
    }

    @Test
    void verdictFromAnalyze_roundTripsIntoRenderRequest() {
        // Verdict đi ai -> Java -> ai: không được rơi hay thêm field nào.
        JsonNode fixture = ContractCompat.load("analyze").get("otp.json");
        AiResponse response = AiCodec.fromTree(fixture.get("response"), AiResponse.class);

        JsonNode sent = AiCodec.toTree(response.analyze().verdict());

        ContractCompat.assertCompatible("verdict", fixture.get("response").get("analyze").get("verdict"), sent);
        assertThat(response.analyze().verdict().shouldAlert()).isTrue();
        assertThat(response.analyze().verdict().riskyActions()).containsExactly("share_otp");
    }

    private static <T> void check(String op, Class<T> requestType) {
        Map<String, JsonNode> fixtures = ContractCompat.load(op);
        assertThat(fixtures).isNotEmpty();
        fixtures.forEach((name, fixture) -> {
            String where = op + "/" + name;

            JsonNode requestNode = fixture.get("request");
            T request = AiCodec.fromTree(requestNode, requestType);
            ContractCompat.assertCompatible(where + ".request", requestNode, AiCodec.toTree(request));

            JsonNode responseNode = fixture.get("response");
            ContractCompat.assertExactlyOne(responseNode);
            AiResponse response = AiCodec.fromTree(responseNode, AiResponse.class);
            assertThat(response.op()).as(where).isEqualTo(op);
            ContractCompat.assertCompatible(where + ".response", responseNode, AiCodec.toTree(response));
        });
    }
}
