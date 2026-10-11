package com.loudsight.syn.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ConsoleOutputTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private ConsoleOutput output(boolean json) {
        return new ConsoleOutput(json, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private static Map<String, Object> data() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", 2);
        data.put("name", "x");
        return data;
    }

    @Test
    void humanModePrintsTheTextToStdout() {
        output(false).emit(data(), "2 items");

        assertEquals("2 items" + System.lineSeparator(), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void jsonModePrintsOneParseableDocumentAndNothingElseToStdout() {
        ConsoleOutput output = output(true);
        output.println("working...");
        output.emit(data(), "2 items");

        JsonNode parsed = JsonMapper.builder().build().readTree(out.toString(StandardCharsets.UTF_8));
        assertEquals(2, parsed.get("count").asInt());
        assertEquals("x", parsed.get("name").asString());
        assertEquals("working..." + System.lineSeparator(), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void theFreeFormStreamIsWhereInfoWrites() {
        output(false).stream().printf(Locale.ROOT, "%d items%n", 2);
        output(true).stream().println("to stderr");

        assertEquals("2 items" + System.lineSeparator(), out.toString(StandardCharsets.UTF_8));
        assertEquals("to stderr" + System.lineSeparator(), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void errorsGoToStderrInBothModes() {
        output(false).errorln("bad");
        output(true).errorln("worse");

        assertEquals("bad" + System.lineSeparator() + "worse" + System.lineSeparator(), err.toString(StandardCharsets.UTF_8));
        assertEquals("", out.toString(StandardCharsets.UTF_8));
    }
}
