package com.askfirst.worker;

import com.askfirst.common.ai.AiContract.Button;
import com.askfirst.worker.TelegramGateway.Btn;
import java.util.ArrayList;
import java.util.List;

/**
 * Đổi {@code Button.kind} do ai trả thành callback_data (khớp chú thích trong models.py).
 * Telegram giới hạn callback_data 64 byte: mọi mẫu dưới đây ngắn hơn (id tối đa 33 ký tự).
 */
final class ButtonMapper {

    private ButtonMapper() {
    }

    static List<List<Btn>> map(List<List<Button>> rows, String checkId, String alertId) {
        List<List<Btn>> out = new ArrayList<>();
        for (List<Button> row : rows) {
            List<Btn> mapped = new ArrayList<>();
            for (Button b : row) {
                String data = callback(b.kind(), checkId, alertId);
                if (data != null) {
                    mapped.add(new Btn(b.label(), data));
                }
            }
            if (!mapped.isEmpty()) {
                out.add(mapped);
            }
        }
        return out;
    }

    /** null nếu kind lạ hoặc thiếu id cần thiết: bỏ nút thay vì gửi callback sai. */
    static String callback(String kind, String checkId, String alertId) {
        if (kind == null) {
            return null;
        }
        return switch (kind) {
            case "checklist" -> "cl";
            case "call_guardian" -> checkId == null ? null : "cg:" + checkId;
            case "alert_guardian" -> checkId == null ? null : "ag:" + checkId;
            case "report_wrong" -> checkId == null ? null : "fb:wrong:" + checkId;
            case "donate" -> checkId == null ? null : "dn:" + checkId;
            case "answer_yes" -> checkId == null ? null : "fu:" + checkId + ":y";
            case "answer_no" -> checkId == null ? null : "fu:" + checkId + ":n";
            case "answer_unknown" -> checkId == null ? null : "fu:" + checkId + ":u";
            case "ack" -> alertId == null ? null : "ack:" + alertId;
            default -> null;
        };
    }
}
