package com.loudsight.syn.core;

import com.loudsight.syn.spi.Output;
import java.io.PrintStream;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link Output} on a pair of streams. Human mode prints text to {@code out}; JSON mode prints one
 * indented JSON document per {@code emit} to {@code out} and sends everything else to {@code err}.
 */
public final class ConsoleOutput implements Output {

    private static final JsonMapper MAPPER = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private final boolean json;
    private final PrintStream out;
    private final PrintStream err;

    public ConsoleOutput(boolean json, PrintStream out, PrintStream err) {
        this.json = json;
        this.out = out;
        this.err = err;
    }

    @Override
    public void emit(Object data, String human) {
        out.println(json ? MAPPER.writeValueAsString(data) : human);
    }

    @Override
    public void println(String message) {
        stream().println(message);
    }

    @Override
    public PrintStream stream() {
        return json ? err : out;
    }

    @Override
    public void errorln(String message) {
        err.println(message);
    }
}
