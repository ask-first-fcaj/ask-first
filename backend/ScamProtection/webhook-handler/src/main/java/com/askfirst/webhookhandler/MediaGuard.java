package com.askfirst.webhookhandler;

import com.askfirst.common.channel.model.InboundMessage;
import com.askfirst.common.channel.model.MediaRef;

import java.util.function.BooleanSupplier;

/**
 * BE-34: chặn media quá nặng / quá dài ngay tại webhook, TRƯỚC khi tạo job.
 *
 * <p>Chỉ đọc metadata mà Telegram đã gửi kèm update ({@code file_size}, {@code duration}); không tải file,
 * không gọi mạng. Nhờ vậy việc từ chối rất rẻ và không có gì đi vào BUFFER# hay SQS.
 *
 * <p>Voice trong MVP chưa hỗ trợ (FR2 hoãn): khi {@code feature_voice} tắt, voice/audio MỌI độ dài đều nhận câu
 * "chưa hỗ trợ". Kiểm {@code duration <= 120} vẫn được giữ để bật lại voice sau này mà không phải viết lại.
 */
public final class MediaGuard {

    /** Giới hạn tải file của Bot API (getFile): 20 MB. */
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    /** Voice dài hơn 2 phút thì từ chối (ARCH §6.3). */
    public static final int MAX_VOICE_SECONDS = 120;

    public enum Verdict {
        OK,
        FILE_TOO_LARGE,
        VOICE_TOO_LONG,
        VOICE_UNSUPPORTED
    }

    /** Kết quả kiểm tra. {@code image} chỉ để chọn câu chữ cho đúng ("file ảnh" hay "tệp"). */
    public record Decision(Verdict verdict, boolean image) {

        private static final Decision ACCEPTED = new Decision(Verdict.OK, false);

        public boolean rejected() {
            return verdict != Verdict.OK;
        }

        /** Mã trả về trong body HTTP 200 (dùng để test và đọc log). */
        public String responseCode() {
            return switch (verdict) {
                case OK -> "OK";
                case FILE_TOO_LARGE -> "MEDIA_TOO_LARGE";
                case VOICE_TOO_LONG -> "VOICE_TOO_LONG";
                case VOICE_UNSUPPORTED -> "VOICE_UNSUPPORTED";
            };
        }
    }

    private MediaGuard() {
    }

    public static boolean isAudioKind(String kind) {
        return "voice".equalsIgnoreCase(kind) || "audio".equalsIgnoreCase(kind);
    }

    /**
     * @param voiceEnabled chỉ được gọi khi tin là voice/audio, nên tin ảnh/văn bản không tốn một lần đọc cờ nào.
     */
    public static Decision inspect(InboundMessage message, BooleanSupplier voiceEnabled) {
        if (message == null) {
            return Decision.ACCEPTED;
        }

        if (isAudioKind(message.kind())) {
            if (!voiceEnabled.getAsBoolean()) {
                return new Decision(Verdict.VOICE_UNSUPPORTED, false);
            }
            MediaRef media = message.media();
            if (media != null && media.duration() != null && media.duration() > MAX_VOICE_SECONDS) {
                return new Decision(Verdict.VOICE_TOO_LONG, false);
            }
            if (media != null && isTooLarge(media)) {
                return new Decision(Verdict.FILE_TOO_LARGE, false);
            }
            return Decision.ACCEPTED;
        }

        MediaRef media = message.media();
        if (media != null && isTooLarge(media)) {
            boolean image = "photo".equalsIgnoreCase(message.kind())
                    || (media.mime() != null && media.mime().toLowerCase().startsWith("image/"));
            return new Decision(Verdict.FILE_TOO_LARGE, image);
        }
        // Thiếu file_size (Telegram không bắt buộc gửi) thì không đủ căn cứ để chặn: cho đi tiếp,
        // `ai` vẫn tự từ chối khi getFile báo file quá lớn.
        return Decision.ACCEPTED;
    }

    private static boolean isTooLarge(MediaRef media) {
        return media.size() != null && media.size() > MAX_FILE_BYTES;
    }

    /** Câu trả lời cho người dùng. {@code addressForm} là Bác/Cô/Chú. */
    public static String replyFor(Decision decision, String addressForm) {
        String who = (addressForm == null || addressForm.isBlank()) ? "Bác" : addressForm;
        return switch (decision.verdict()) {
            case FILE_TOO_LARGE -> decision.image()
                    ? who + " ơi, file ảnh này dung lượng hơi lớn (quá 20MB) nên cháu chưa đọc được. "
                            + who + " chụp lại màn hình gọn hơn giúp cháu nhé!"
                    : who + " ơi, tệp này dung lượng hơi lớn (quá 20MB) nên cháu chưa đọc được. "
                            + who + " chụp màn hình nội dung cần kiểm tra giúp cháu nhé!";
            case VOICE_TOO_LONG -> who + " ơi, đoạn ghi âm dài quá 2 phút rồi ạ. "
                    + who + " gửi đoạn ngắn hơn giúp cháu nhé!";
            case VOICE_UNSUPPORTED -> who + " ơi, hiện cháu chưa hỗ trợ tin nhắn thoại (voice) ạ. "
                    + who + " chụp màn hình hoặc gõ lại nội dung cần kiểm tra giúp cháu nhé!";
            case OK -> "";
        };
    }
}
