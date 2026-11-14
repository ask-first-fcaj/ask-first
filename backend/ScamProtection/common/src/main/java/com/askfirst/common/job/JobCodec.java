package com.askfirst.common.job;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Chuyển {@link Job} <-> JSON body của SQS message.
 * Producer (webhook) dùng {@link #toJson}, consumer (worker) dùng {@link #parse}.
 * Lỗi contract luôn ra {@link InvalidJobException} chỉ có mã lỗi: caller quyết định retry/DLQ/ack.
 */
public final class JobCodec {

    // Tắt INCLUDE_SOURCE_IN_LOCATION để message lỗi của Jackson không kèm đoạn body gốc.
    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder().disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build())
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JobCodec() {
    }

    public static Job parse(String body) {
        if (body == null || body.isBlank()) {
            throw new InvalidJobException(InvalidJobException.MALFORMED_JSON);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new InvalidJobException(InvalidJobException.MALFORMED_JSON);
        }
        if (root == null || !root.isObject()) {
            throw new InvalidJobException(InvalidJobException.MALFORMED_JSON);
        }

        JsonNode v = root.get("v");
        if (v == null || !v.isInt()) {
            throw InvalidJobException.invalidField("v");
        }
        if (v.intValue() != Job.CURRENT_VERSION) {
            throw new InvalidJobException(InvalidJobException.UNSUPPORTED_VERSION);
        }
        JsonNode type = root.get("type");
        if (type == null || !type.isTextual()) {
            throw new InvalidJobException(InvalidJobException.UNKNOWN_TYPE);
        }

        try {
            return MAPPER.treeToValue(root, Job.class);
        } catch (JsonProcessingException e) {
            // Constructor của record ném InvalidJobException, Jackson bọc lại: lấy ra bản gốc.
            if (e.getCause() instanceof InvalidJobException invalid) {
                throw invalid;
            }
            if (e instanceof com.fasterxml.jackson.databind.exc.InvalidTypeIdException) {
                throw new InvalidJobException(InvalidJobException.UNKNOWN_TYPE);
            }
            throw new InvalidJobException(InvalidJobException.INVALID_FIELD);
        }
    }

    public static String toJson(Job job) {
        try {
            return MAPPER.writeValueAsString(job);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không serialize được job " + job.getClass().getSimpleName());
        }
    }
}
