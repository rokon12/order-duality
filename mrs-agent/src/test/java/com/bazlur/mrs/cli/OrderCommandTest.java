package com.bazlur.mrs.cli;

import static org.junit.jupiter.api.Assertions.*;

import module java.base;

import com.bazlur.mrs.agent.OrderAgent;
import com.bazlur.mrs.client.MrsClient;
import com.bazlur.mrs.domain.SessionExpired;
import dev.langchain4j.exception.ModelNotFoundException;
import org.junit.jupiter.api.Test;

class OrderCommandTest {
    record Streams(String out, String err) {}

    @Test
    void helpAndVersionGoToStandardOutputWithoutStartingTheApplication() {
        var output =
                capture(
                        () -> {
                            assertEquals(
                                    0,
                                    CliSupport.commandLine(new OrderCommand()).execute("--help"));
                            assertEquals(
                                    0,
                                    CliSupport.commandLine(new OrderCommand())
                                            .execute("--version"));
                        });
        assertTrue(output.out().contains("--ask"));
        assertTrue(output.out().contains("--read"));
        assertTrue(output.out().contains("mrs-agent 1.0.0"));
        assertEquals("", output.err());
    }

    @Test
    void invalidArgumentsFailBeforeAuthentication() {
        capture(
                () -> {
                    for (String[] args :
                            List.of(
                                    new String[] {"--unknown"},
                                    new String[] {"--read"},
                                    new String[] {"--read", "0"},
                                    new String[] {"--read", "-1"},
                                    new String[] {"--read", "not-an-id"},
                                    new String[] {"--read", "9223372036854775808"},
                                    new String[] {"--list", "--requests"},
                                    new String[] {"--list", "--ask", "List orders"},
                                    new String[] {"--ask", " "},
                                    new String[] {"--ask", "x".repeat(2001)},
                                    new String[] {"--list=false"})) {
                        assertEquals(
                                2,
                                CliSupport.commandLine(new OrderCommand()).execute(args),
                                Arrays.toString(args));
                    }
                });
    }

    @Test
    void userErrorsGoToStandardErrorWithoutLogLevels() {
        var output =
                capture(
                        () ->
                                assertEquals(
                                        2,
                                        CliSupport.commandLine(new OrderCommand())
                                                .execute("--ask", " ")));
        assertTrue(output.err().contains("Question must contain"), output.err());
        assertFalse(output.err().contains("ERROR"), output.err());
        assertEquals("", output.out());
    }

    @Test
    void parseErrorsDoNotEchoSensitiveArguments() {
        String secret = "example-secret-that-must-not-be-logged";
        var output =
                capture(
                        () ->
                                assertEquals(
                                        2,
                                        CliSupport.commandLine(new OrderCommand())
                                                .execute("--password", secret)));
        assertTrue(output.err().contains("Invalid command or arguments"));
        assertFalse(output.err().contains(secret));
        assertFalse(output.out().contains(secret));
    }

    @Test
    void argumentFilesCannotSupplyCommands() throws Exception {
        var file = Files.createTempFile("mrs-cli-args", ".txt");
        try {
            Files.writeString(file, "--help");
            capture(
                    () ->
                            assertEquals(
                                    2,
                                    CliSupport.commandLine(new OrderCommand())
                                            .execute("@" + file)));
        } finally {
            Files.delete(file);
        }
    }

    @Test
    void failuresOmitDownstreamPayloads() {
        String message = CliSupport.failureMessage(new IOException("private-bearer-token"));
        assertTrue(message.contains("IOException"), message);
        assertTrue(message.contains("No success is assumed"), message);
        assertFalse(message.contains("private-bearer-token"), message);
    }

    @Test
    void commonFailuresSayWhatToDoNext() {
        assertFailure(new OrderCommand.MissingConfiguration("MRS_PASSWORD"), "MRS_PASSWORD");
        assertFailure(new MrsClient.Failure(401), "Sign-in failed");
        assertFailure(new SessionExpired(), "restart");
        assertFailure(new MrsClient.Failure(403), "not allowed");
        assertFailure(new MrsClient.Failure(404), "not found");
        assertFailure(new MrsClient.Failure(500), "HTTP 500");
        assertFailure(new ModelNotFoundException("secret-detail"), "ollama pull");
        assertFailure(new ConnectException("secret-detail"), "mrs-up.sh");
        assertFailure(new NoSuchFileException("secret-detail"), "MRS_CA");
        assertFailure(
                new RuntimeException("secret-detail", new ConnectException("secret-detail")),
                "Ollama");
        assertFailure(new OrderAgent.NoAnswer("Fixed text for the customer."), "Fixed text");
    }

    private static void assertFailure(Exception error, String expected) {
        String message = CliSupport.failureMessage(error);
        assertTrue(message.contains(expected), message);
        assertFalse(message.contains("secret-detail"), message);
    }

    static Streams capture(Runnable action) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var previousOut = System.out;
        var previousErr = System.err;
        try (var outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
                var errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setOut(outStream);
            System.setErr(errStream);
            action.run();
        } finally {
            System.setOut(previousOut);
            System.setErr(previousErr);
        }
        return new Streams(
                out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }
}
