package com.askfirst.common.ai;

import com.askfirst.common.ai.AiContract.AiResponse;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON cho contract ai. Đọc khoan dung với field lạ (ai thêm field mới không làm worker hỏng),
 * ghi chặt vì Python {@code extra="forbid"}. Không bao giờ đưa đoạn JSON gốc vào exception.
 */
public final class AiCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder().disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).build())
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private AiCodec() {
    }

    public static String toJson(Object request) {
        try {
            return MAPPER.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Không serialize được " + request.getClass().getSimpleName());
        }
    }

    public static JsonNode toTree(Object value) {
        return MAPPER.valueToTree(value);
    }

    /** Dùng cho test contract. Lỗi parse ra {@link IllegalArgumentException} không kèm nội dung. */
    public static <T> T fromTree(JsonNode node, Class<T> type) {
        try {
            return MAPPER.treeToValue(node, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Không đọc được " + type.getSimpleName());
        }
    }

    /** Đọc JSON thành record contract (ví dụ InboundMessage từ BUFFER#). Lỗi ra IllegalArgumentException không kèm nội dung. */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Không đọc được " + type.getSimpleName());
        }
    }

    /** Body không parse được hoặc rỗng: retryable "invoke" (theo plan). */
    public static AiResponse parseResponse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AiException.retryable("invoke");
        }
        try {
            AiResponse parsed = MAPPER.readValue(raw, AiResponse.class);
            if (parsed == null) {
                throw AiException.retryable("invoke");
            }
            return parsed;
        } catch (JsonProcessingException e) {
            throw AiException.retryable("invoke");
        }
    }
}
