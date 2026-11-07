package com.bazlur.mrs.cli;

import module java.base;

import com.bazlur.mrs.agent.OrderAgent;
import com.bazlur.mrs.client.MrsClient;
import com.bazlur.mrs.domain.SessionExpired;
import com.bazlur.mrs.workflow.Confirmation;
import dev.langchain4j.exception.ModelNotFoundException;
import dev.langchain4j.exception.TimeoutException;
import picocli.CommandLine;

/** Shared picocli setup and sanitized failure messages. */
final class CliSupport {
    static CommandLine commandLine(Object command) {
        return new CommandLine(command)
                .setExpandAtFiles(false)
                .setColorScheme(
                        new CommandLine.Help.ColorScheme.Builder(CommandLine.Help.Ansi.OFF).build())
                .setParameterExceptionHandler(
                        (error, args) -> {
                            var err = error.getCommandLine().getErr();
                            err.println("Invalid command or arguments. Use --help for syntax.");
                            error.getCommandLine().usage(err);
                            return 2;
                        })
                .setExecutionExceptionHandler(
                        (error, commandLine, parseResult) -> {
                            commandLine.getErr().println(failureMessage(error));
                            return 1;
                        });
    }

    /**
     * Exception text can contain downstream payloads or credentials, so only fixed text reaches the
     * screen. The exceptions whose messages are shown carry fixed text of their own.
     */
    static String failureMessage(Exception error) {
        return switch (error) {
            case OrderCommand.MissingConfiguration missing -> missing.getMessage();
            case OrderAgent.NoAnswer noAnswer -> noAnswer.getMessage();
            case Confirmation.RequestChanged changed -> changed.getMessage();
            case SessionExpired expired ->
                    "Your session expired and signing in again failed. Check your password settings, then restart.";
            case MrsClient.Failure failure ->
                    switch (failure.status()) {
                        case 401 ->
                                "Sign-in failed. Check MRS_USER, MRS_PASSWORD, and MRS_EXECUTOR_PASSWORD.";
                        case 403 -> "Your account is not allowed to do that.";
                        case 404 -> "That request was not found, or it is not yours.";
                        default ->
                                "The order service returned HTTP "
                                        + failure.status()
                                        + ". No success is assumed; check your cancellation requests before retrying.";
                    };
            case ModelNotFoundException notFound ->
                    "That language model is not installed. Run: ollama pull <model>, or set OLLAMA_MODEL.";
            case TimeoutException timeout ->
                    "The language model took too long. Check your cancellation requests before retrying, or raise OLLAMA_TIMEOUT_SECONDS.";
            case ConnectException connect ->
                    "Could not reach the order service. Start it with: bash scripts/mrs-up.sh";
            case NoSuchFileException missing ->
                    "The TLS certificate was not found. Start the service with scripts/mrs-up.sh, or set MRS_CA.";
            default ->
                    causedByConnection(error)
                            ? "Could not reach the language model. Is Ollama running? Start it with: ollama serve"
                            : "That did not work ("
                                    + error.getClass().getSimpleName()
                                    + "). No success is assumed; check your cancellation requests before retrying.";
        };
    }

    private static boolean causedByConnection(Throwable error) {
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    private CliSupport() {}
}
