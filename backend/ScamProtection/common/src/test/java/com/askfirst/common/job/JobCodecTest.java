package com.askfirst.common.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobCodecTest {

    private static final Instant NOW = Instant.parse("2026-10-14T07:05:00Z");
    private static final String ID = "01J00000000000000000000001";

    @Test
    void analyzeJob_roundTrip_andWireFormat() throws Exception {
        AnalyzeJob job = AnalyzeJob.create(ID, "123456789", "tg:123456789", 3, NOW);

        String json = JobCodec.toJson(job);
        JsonNode node = new ObjectMapper().readTree(json);

        assertThat(node.get("type").asText()).isEqualTo("analyze");
        assertThat(node.get("v").asInt()).isEqualTo(1);
        assertThat(node.get("chat_id").isTextual()).isTrue();
        assertThat(node.get("enqueued_at").asText()).isEqualTo("2026-10-14T07:05:00Z");
        assertThat(JobCodec.parse(json)).isEqualTo(job);
    }

    @Test
    void followupJob_roundTrip() {
        FollowupJob job = FollowupJob.create(ID, "123", "tg:123", ID, List.of(ID), NOW);

        assertThat(JobCodec.parse(JobCodec.toJson(job))).isEqualTo(job);
    }

    @Test
    void answerJob_roundTrip() {
        AnswerJob job = new AnswerJob(1, ID, "123", "tg:123", ID, "yes", "77", NOW);

        assertThat(JobCodec.parse(JobCodec.toJson(job))).isEqualTo(job);
    }

    @Test
    void chatIdSentAsNumber_isCoercedToString() {
        String body = """
                {"v":1,"type":"analyze","job_id":"%s","chat_id":123456789,
                 "user_id":"tg:123456789","seq":1,"enqueued_at":"2026-10-14T07:05:00Z"}""".formatted(ID);

        assertThat(((AnalyzeJob) JobCodec.parse(body)).chatId()).isEqualTo("123456789");
    }

    @Test
    void unsupportedVersion_isRejected() {
        String body = """
                {"v":2,"type":"analyze","job_id":"%s","chat_id":"1","user_id":"tg:1","seq":1,
                 "enqueued_at":"2026-10-14T07:05:00Z"}""".formatted(ID);

        assertThatThrownBy(() -> JobCodec.parse(body))
                .isInstanceOfSatisfying(InvalidJobException.class,
                        e -> assertThat(e.code()).isEqualTo(InvalidJobException.UNSUPPORTED_VERSION));
    }

    @Test
    void unknownType_isRejected() {
        String body = "{\"v\":1,\"type\":\"nope\",\"job_id\":\"" + ID + "\"}";

        assertThatThrownBy(() -> JobCodec.parse(body))
                .isInstanceOfSatisfying(InvalidJobException.class,
                        e -> assertThat(e.code()).isEqualTo(InvalidJobException.UNKNOWN_TYPE));
    }

    @Test
    void legacyBodyWithoutVersion_isRejected() {
        assertThatThrownBy(() -> JobCodec.parse("{\"chatId\":123}"))
                .isInstanceOf(InvalidJobException.class);
    }

    @Test
    void missingRequiredField_namesFieldButNotValue() {
        String body = "{\"v\":1,\"type\":\"analyze\",\"job_id\":\"" + ID
                + "\",\"chat_id\":\"1\",\"seq\":1,\"enqueued_at\":\"2026-10-14T07:05:00Z\"}";

        assertThatThrownBy(() -> JobCodec.parse(body))
                .isInstanceOfSatisfying(InvalidJobException.class,
                        e -> assertThat(e.code()).isEqualTo("invalid_field:user_id"));
    }

    @Test
    void malformedJson_doesNotLeakBodyIntoException() {
        String secretText = "NOI-DUNG-NHAY-CAM-OTP-123456";
        String body = "{\"v\":1, " + secretText;

        assertThatThrownBy(() -> JobCodec.parse(body))
                .isInstanceOfSatisfying(InvalidJobException.class, e -> {
                    assertThat(e.code()).isEqualTo(InvalidJobException.MALFORMED_JSON);
                    assertThat(e.getMessage()).doesNotContain(secretText);
                    assertThat(e.getCause()).isNull();
                });
    }

    @Test
    void blankOrNullBody_isMalformed() {
        assertThatThrownBy(() -> JobCodec.parse(" ")).isInstanceOf(InvalidJobException.class);
        assertThatThrownBy(() -> JobCodec.parse(null)).isInstanceOf(InvalidJobException.class);
    }

    @Test
    void followupWithoutAlertIds_isRejected() {
        assertThatThrownBy(() -> FollowupJob.create(ID, "1", "tg:1", ID, List.of(), NOW))
                .isInstanceOf(InvalidJobException.class);
    }
}
