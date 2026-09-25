package com.agentsdlc.orchestrator.approval;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.Optional;

/**
 * Shared, synchronized question-and-answer channel on a terminal. Tasks in
 * a parallel wave may ask at the same time; one lock keeps question and
 * answer paired.
 */
public final class ConsolePrompter {

    private final BufferedReader in;
    private final PrintStream out;

    /**
     * Creates the prompter.
     *
     * @param in  where answers are read from
     * @param out where questions are printed
     */
    public ConsolePrompter(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    /**
     * Prints a question and reads one line.
     *
     * @param question text to show
     * @return the answer, or empty when input has ended (non-interactive run)
     */
    public synchronized Optional<String> ask(String question) {
        out.print(question);
        out.flush();
        try {
            String line = in.readLine();
            return Optional.ofNullable(line).map(String::trim);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Prints a line.
     *
     * @param text text to print
     */
    public synchronized void say(String text) {
        out.println(text);
    }
}
