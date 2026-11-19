package com.askfirst.webhookhandler;

import static org.assertj.core.api.Assertions.assertThat;

import com.askfirst.common.channel.model.InboundMessage;
import com.askfirst.common.channel.model.MediaRef;
import com.askfirst.webhookhandler.MediaGuard.Decision;
import com.askfirst.webhookhandler.MediaGuard.Verdict;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** BE-34: kiểm tra thuần logic, không cần AWS. Payload giả lập: ảnh 25 MB, voice 150 giây. */
class MediaGuardTest {

    private static final long MB = 1024L * 1024;

    private static InboundMessage msg(String kind, String mime, Long size, Integer duration) {
        MediaRef media = new MediaRef("f", "u", mime, size, duration, null);
        return new InboundMessage("telegram", "1", "2", "tg:3", "4", kind, null, List.of(), media, null, null, Instant.now());
    }

    private static InboundMessage text() {
        return new InboundMessage("telegram", "1", "2", "tg:3", "4", "text", "xin chào", List.of(), null, null, null,
                Instant.now());
    }

    @Test
    @DisplayName("ảnh 25 MB bị chặn")
    void photo25MbRejected() {
        Decision d = MediaGuard.inspect(msg("photo", "image/jpeg", 25 * MB, null), () -> false);
        assertThat(d.verdict()).isEqualTo(Verdict.FILE_TOO_LARGE);
        assertThat(d.rejected()).isTrue();
        assertThat(d.image()).isTrue();
    }

    @Test
    @DisplayName("ranh giới: 20 MB nhận, 20 MB + 1 byte chặn")
    void sizeBoundary() {
        assertThat(MediaGuard.inspect(msg("photo", "image/jpeg", 20 * MB, null), () -> false).rejected()).isFalse();
        assertThat(MediaGuard.inspect(msg("photo", "image/jpeg", 20 * MB + 1, null), () -> false).rejected()).isTrue();
    }

    @Test
    @DisplayName("thiếu file_size thì không đủ căn cứ để chặn")
    void unknownSizeAccepted() {
        assertThat(MediaGuard.inspect(msg("photo", "image/jpeg", null, null), () -> false).rejected()).isFalse();
    }

    @Test
    @DisplayName("văn bản không có media thì luôn qua")
    void textAccepted() {
        assertThat(MediaGuard.inspect(text(), () -> false).rejected()).isFalse();
        assertThat(MediaGuard.inspect(null, () -> false).rejected()).isFalse();
    }

    @Test
    @DisplayName("document: image/* là 'file ảnh', loại khác không")
    void documentImageFlag() {
        assertThat(MediaGuard.inspect(msg("document", "image/png", 30 * MB, null), () -> false).image()).isTrue();
        assertThat(MediaGuard.inspect(msg("document", "application/pdf", 30 * MB, null), () -> false).image()).isFalse();
    }

    @Test
    @DisplayName("voice 150 giây, cờ tắt: 'chưa hỗ trợ'")
    void voice150sFlagOff() {
        assertThat(MediaGuard.inspect(msg("voice", "audio/ogg", 30_000L, 150), () -> false).verdict())
                .isEqualTo(Verdict.VOICE_UNSUPPORTED);
    }

    @Test
    @DisplayName("voice mọi độ dài, cờ tắt: 'chưa hỗ trợ'")
    void voiceAnyLengthFlagOff() {
        for (int seconds : new int[] {1, 30, 120, 121, 3600}) {
            assertThat(MediaGuard.inspect(msg("voice", "audio/ogg", 1000L, seconds), () -> false).verdict())
                    .as("%d giây", seconds).isEqualTo(Verdict.VOICE_UNSUPPORTED);
        }
        assertThat(MediaGuard.inspect(msg("audio", "audio/mpeg", 1000L, 10), () -> false).verdict())
                .isEqualTo(Verdict.VOICE_UNSUPPORTED);
    }

    @Test
    @DisplayName("cờ bật: 150 giây quá dài, đúng 120 giây vẫn nhận")
    void voiceDurationWhenEnabled() {
        assertThat(MediaGuard.inspect(msg("voice", "audio/ogg", 1000L, 150), () -> true).verdict())
                .isEqualTo(Verdict.VOICE_TOO_LONG);
        assertThat(MediaGuard.inspect(msg("voice", "audio/ogg", 1000L, 121), () -> true).verdict())
                .isEqualTo(Verdict.VOICE_TOO_LONG);
        assertThat(MediaGuard.inspect(msg("voice", "audio/ogg", 1000L, 120), () -> true).rejected()).isFalse();
    }

    @Test
    @DisplayName("cờ bật: voice > 20 MB vẫn bị chặn theo dung lượng")
    void voiceSizeWhenEnabled() {
        assertThat(MediaGuard.inspect(msg("voice", "audio/ogg", 25 * MB, 60), () -> true).verdict())
                .isEqualTo(Verdict.FILE_TOO_LARGE);
    }

    @Test
    @DisplayName("cờ voice chỉ được hỏi khi tin là voice/audio")
    void flagReadOnlyForAudio() {
        AtomicInteger reads = new AtomicInteger();
        java.util.function.BooleanSupplier flag = () -> {
            reads.incrementAndGet();
            return true;
        };
        MediaGuard.inspect(text(), flag);
        MediaGuard.inspect(msg("photo", "image/jpeg", 1000L, null), flag);
        assertThat(reads.get()).isZero();
        MediaGuard.inspect(msg("voice", "audio/ogg", 1000L, 5), flag);
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("câu trả lời đúng nguyên văn theo issue, xưng hô theo cài đặt")
    void replyWording() {
        assertThat(MediaGuard.replyFor(new Decision(Verdict.FILE_TOO_LARGE, true), "Bác")).isEqualTo(
                "Bác ơi, file ảnh này dung lượng hơi lớn (quá 20MB) nên cháu chưa đọc được. "
                        + "Bác chụp lại màn hình gọn hơn giúp cháu nhé!");
        assertThat(MediaGuard.replyFor(new Decision(Verdict.VOICE_TOO_LONG, false), "Bác")).isEqualTo(
                "Bác ơi, đoạn ghi âm dài quá 2 phút rồi ạ. Bác gửi đoạn ngắn hơn giúp cháu nhé!");
        assertThat(MediaGuard.replyFor(new Decision(Verdict.VOICE_UNSUPPORTED, false), "Chú"))
                .startsWith("Chú ơi, hiện cháu chưa hỗ trợ tin nhắn thoại");
        assertThat(MediaGuard.replyFor(new Decision(Verdict.FILE_TOO_LARGE, true), null)).startsWith("Bác ơi");
    }
}
