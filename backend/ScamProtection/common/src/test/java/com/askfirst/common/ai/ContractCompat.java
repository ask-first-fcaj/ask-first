package com.askfirst.common.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Helper cho test contract, không phụ thuộc JUnit (ném AssertionError) để chạy được ở mọi nơi.
 * Fixture trong contracts/fixtures liệt kê đầy đủ field (kể cả null), nên dùng được làm "khuôn":
 * request Java sinh ra không được có field nào ngoài khuôn (Python extra="forbid" sẽ từ chối),
 * và mọi giá trị khác null của khuôn phải xuất hiện đúng trong JSON Java sinh ra.
 */
final class ContractCompat {

    private static final ObjectMapper PLAIN = new ObjectMapper();

    private ContractCompat() {
    }

    /** Tìm thư mục contracts/fixtures: -Dcontracts.dir, hoặc đi ngược từ thư mục hiện tại. Không thấy thì FAIL (không skip). */
    static Path fixturesDir() {
        String override = System.getProperty("contracts.dir");
        if (override != null && !override.isBlank()) {
            return Path.of(override).resolve("fixtures");
        }
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("contracts").resolve("fixtures");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new AssertionError("Không tìm thấy contracts/fixtures (đã đi ngược từ " + Path.of("").toAbsolutePath()
                + "). Chạy từ trong repo hoặc truyền -Dcontracts.dir=<đường dẫn contracts>");
    }

    /** Đọc mọi fixture của một op: tên file -> node gốc (có "request" và "response"). */
    static Map<String, JsonNode> load(String op) {
        Path dir = fixturesDir().resolve(op);
        Map<String, JsonNode> result = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".json")).sorted()::iterator) {
                result.put(p.getFileName().toString(), PLAIN.readTree(p.toFile()));
            }
        } catch (IOException e) {
            throw new AssertionError("Không đọc được fixtures của " + op + ": " + e.getMessage());
        }
        if (result.isEmpty()) {
            throw new AssertionError("Không có fixture nào cho " + op);
        }
        return result;
    }

    /** So JSON Java sinh ra ({@code actual}) với khuôn fixture ({@code expected}). */
    static void assertCompatible(String path, JsonNode expected, JsonNode actual) {
        if (expected == null || expected.isNull()) {
            if (actual != null && !actual.isNull()) {
                throw new AssertionError(path + ": fixture là null nhưng Java sinh ra giá trị");
            }
            return;
        }
        if (actual == null || actual.isNull()) {
            throw new AssertionError(path + ": fixture có giá trị nhưng Java không sinh ra");
        }
        if (expected.isObject()) {
            if (!actual.isObject()) {
                throw new AssertionError(path + ": phải là object");
            }
            Iterator<String> names = actual.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (!expected.has(name)) {
                    throw new AssertionError(path + "." + name + ": field không có trong contract (Python sẽ từ chối)");
                }
            }
            Iterator<String> expectedNames = expected.fieldNames();
            while (expectedNames.hasNext()) {
                String name = expectedNames.next();
                assertCompatible(path + "." + name, expected.get(name), actual.get(name));
            }
        } else if (expected.isArray()) {
            if (!actual.isArray() || actual.size() != expected.size()) {
                throw new AssertionError(path + ": mảng khác kích thước");
            }
            for (int i = 0; i < expected.size(); i++) {
                assertCompatible(path + "[" + i + "]", expected.get(i), actual.get(i));
            }
        } else if (expected.isNumber()) {
            if (!actual.isNumber() || Double.compare(expected.doubleValue(), actual.doubleValue()) != 0) {
                throw new AssertionError(path + ": số khác nhau");
            }
        } else if (path.endsWith("received_at") || path.endsWith(".at")) {
            if (!Instant.parse(expected.asText()).equals(Instant.parse(actual.asText()))) {
                throw new AssertionError(path + ": thời điểm khác nhau");
            }
        } else if (!expected.equals(actual)) {
            throw new AssertionError(path + ": giá trị khác nhau");
        }
    }

    /** AiResponse hợp lệ: đúng một field kết quả khớp op, hoặc error (giống _exactly_one của models.py). */
    static void assertExactlyOne(JsonNode response) {
        List<String> filled = new ArrayList<>();
        for (String key : List.of("analyze", "render_guardian_alerts", "render", "error")) {
            JsonNode n = response.get(key);
            if (n != null && !n.isNull()) {
                filled.add(key);
            }
        }
        String op = response.path("op").asText();
        if (filled.size() != 1 || (!filled.get(0).equals("error") && !filled.get(0).equals(op))) {
            throw new AssertionError("op=" + op + " phải có đúng một kết quả cùng tên hoặc error, thấy " + filled);
        }
    }
}
