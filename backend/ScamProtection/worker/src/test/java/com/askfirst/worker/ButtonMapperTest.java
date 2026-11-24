package com.askfirst.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.askfirst.common.ai.AiContract.Button;
import java.util.List;
import org.junit.jupiter.api.Test;

class ButtonMapperTest {

    private static final String CHECK = "01JOB00000000000000000000A";

    @Test
    void everyKind_mapsToDocumentedCallback() {
        assertEquals("cl", ButtonMapper.callback("checklist", CHECK, null));
        assertEquals("cg:" + CHECK, ButtonMapper.callback("call_guardian", CHECK, null));
        assertEquals("ag:" + CHECK, ButtonMapper.callback("alert_guardian", CHECK, null));
        assertEquals("fb:wrong:" + CHECK, ButtonMapper.callback("report_wrong", CHECK, null));
        assertEquals("dn:" + CHECK, ButtonMapper.callback("donate", CHECK, null));
        assertEquals("fu:" + CHECK + ":y", ButtonMapper.callback("answer_yes", CHECK, null));
        assertEquals("fu:" + CHECK + ":n", ButtonMapper.callback("answer_no", CHECK, null));
        assertEquals("fu:" + CHECK + ":u", ButtonMapper.callback("answer_unknown", CHECK, null));
        assertEquals("ack:ALERT1", ButtonMapper.callback("ack", null, "ALERT1"));
    }

    @Test
    void unknownKindOrMissingId_isDropped() {
        assertNull(ButtonMapper.callback("something_new", CHECK, null));
        assertNull(ButtonMapper.callback("ack", CHECK, null));
        assertNull(ButtonMapper.callback(null, CHECK, null));
        assertTrue(ButtonMapper.map(List.of(List.of(new Button("something_new", "x"))), CHECK, null).isEmpty());
    }

    @Test
    void longestCallback_fitsTelegramLimit() {
        assertTrue(ButtonMapper.callback("report_wrong", CHECK, null).getBytes().length <= 64);
        assertTrue(("ack:" + AnalyzeProcessor.alertId(CHECK, "tg:1")).getBytes().length <= 64);
    }
}
