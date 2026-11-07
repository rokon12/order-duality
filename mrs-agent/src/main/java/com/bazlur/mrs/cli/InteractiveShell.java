package com.bazlur.mrs.cli;

import module java.base;

import com.bazlur.mrs.agent.OrderAgent;
import com.bazlur.mrs.client.MrsClient;
import com.bazlur.mrs.workflow.Confirmation;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

/** Trusted terminal commands are parsed separately from model questions. */
@Command(
        name = "session",
        mixinStandardHelpOptions = true,
        subcommands = CommandLine.HelpCommand.class,
        description = "Prefix a terminal command with /. Other text is an order question.")
public final class InteractiveShell {
    private static final Set<String> HELP_WORDS = Set.of("help", "?", "/help", "/", "-h", "--help");
    private static final Set<String> VERSION_WORDS =
            Set.of("version", "/version", "-v", "--version");
    private static final Set<String> COMMAND_WORDS = Set.of("quit", "exit", "requests", "clear");
    private static final Set<String> APPROVAL_WORDS = Set.of("yes", "y", "no", "n", "confirm");
    private static final String THINKING = "Thinking...";
    private static final String APPROVALS_OFF =
            "Approvals are off. Set MRS_EXECUTOR_PASSWORD and restart to use /confirm.";
    private static final String NO_SELECTION =
            "No request selected. Ask to cancel an order, or use /requests to find one.";

    private final MrsClient customer;
    private final OrderAgent agent;
    private final Confirmation confirmation;
    private final UnaryOperator<String> prompt;
    private final PrintWriter out;
    private UUID latestRequest;
    private boolean running = true;

    public InteractiveShell(
            MrsClient customer,
            OrderAgent agent,
            Confirmation confirmation,
            UnaryOperator<String> prompt,
            PrintWriter out) {
        this.customer = customer;
        this.agent = agent;
        this.confirmation = confirmation;
        this.prompt = prompt;
        this.out = out;
    }

    public void run() {
        var commands =
                CliSupport.commandLine(this)
                        .setSubcommandsCaseInsensitive(true)
                        .setOut(out)
                        .setErr(out)
                        .setParameterExceptionHandler(
                                (error, args) -> {
                                    out.println(
                                            "Invalid command or arguments. Type /help to see what is available.");
                                    return 2;
                                });
        out.println("Order assistant. Ask about your orders, or type /help.");
        if (confirmation == null) {
            out.println(APPROVALS_OFF);
        }
        while (running) {
            out.println();
            String line = prompt.apply("> ");
            if (line == null) {
                out.println();
                return;
            }
            line = line.strip();
            if (!line.isEmpty()) {
                handle(line, commands);
            }
        }
    }

    private void handle(String line, CommandLine commands) {
        String word = line.toLowerCase(Locale.ROOT);
        if (HELP_WORDS.contains(word)) {
            out.println(Screens.HELP);
        } else if (VERSION_WORDS.contains(word)) {
            out.println(OrderCommand.VERSION);
        } else if (line.startsWith("/")) {
            // Slash commands take only command names and UUIDs, so no shell expansion is needed.
            commands.execute(line.substring(1).strip().split("\\s+"));
        } else if (COMMAND_WORDS.contains(word)) {
            commands.execute(word);
        } else if (APPROVAL_WORDS.contains(word) || word.startsWith("confirm ")) {
            // An approval typed here never reaches the model or approves anything.
            out.println(
                    "Nothing to approve here. Enter /confirm to review a request first; it asks yes or no.");
        } else if (line.startsWith("-") && !line.contains(" ")) {
            // A lone flag is a mistyped command, not a question for the model.
            out.println("Commands start with /. Type /help to see them.");
        } else if (line.length() > 2000) {
            out.println("Questions can be up to 2000 characters.");
        } else {
            ask(line);
        }
    }

    private void ask(String question) {
        OrderAgent.Answer answer;
        out.print(THINKING + "\r");
        out.flush();
        try {
            answer = agent.answer(question);
        } catch (Exception error) {
            clearThinking();
            out.println(CliSupport.failureMessage(error));
            return;
        }
        clearThinking();
        out.println(Screens.answer(answer));
        for (var request : answer.requests()) {
            out.println();
            out.println(Screens.recorded(request));
            if (request.path("state").asString().equals("PENDING")) {
                latestRequest = UUID.fromString(request.path("requestId").asString());
                out.println(
                        confirmation == null
                                ? APPROVALS_OFF
                                : "Type /confirm to review and approve it.");
            }
        }
    }

    private void clearThinking() {
        out.print(" ".repeat(THINKING.length()) + "\r");
        out.flush();
    }

    @Command(
            name = "requests",
            mixinStandardHelpOptions = true,
            description = "List your saved cancellation requests.")
    void requests() throws Exception {
        out.println(Screens.requests(customer.cancellations()));
    }

    @Command(
            name = "review",
            mixinStandardHelpOptions = true,
            description = "Show a saved cancellation request and its order.")
    void review(@Parameters(arity = "0..1", paramLabel = "REQUEST_ID") UUID selected)
            throws Exception {
        UUID id = selected == null ? latestRequest : selected;
        if (id == null) {
            out.println(NO_SELECTION);
            return;
        }
        out.println(Screens.review(Confirmation.review(customer, id)));
    }

    @Command(
            name = "clear",
            mixinStandardHelpOptions = true,
            description = "Forget conversation context and the selected request.")
    void clear() {
        agent.clearMemory();
        latestRequest = null;
        out.println("Conversation cleared. Your saved requests are still listed under /requests.");
    }

    @Command(
            name = "quit",
            aliases = "exit",
            mixinStandardHelpOptions = true,
            description = "Exit the interactive session.")
    void quit() {
        running = false;
    }

    @Command(
            name = "confirm",
            mixinStandardHelpOptions = true,
            description = "Review a request and ask for explicit yes/no approval.")
    void confirm(@Parameters(arity = "0..1", paramLabel = "REQUEST_ID") UUID selected)
            throws Exception {
        if (confirmation == null) {
            out.println(APPROVALS_OFF);
            return;
        }
        UUID id = selected == null ? latestRequest : selected;
        if (id == null) {
            out.println(NO_SELECTION);
            return;
        }
        var review = confirmation.review(id);
        out.println(Screens.review(review));
        if (!review.state().equals("PENDING")) {
            out.println(
                    "This request is "
                            + Screens.oneLine(review.state())
                            + ", so there is nothing to approve.");
            return;
        }
        out.println();
        while (true) {
            String response = prompt.apply("Cancel order " + review.orderId() + "? [y/N]: ");
            String answer = response == null ? "" : response.strip().toLowerCase(Locale.ROOT);
            if (answer.isEmpty() || answer.equals("n") || answer.equals("no")) {
                out.println(
                        "Order "
                                + review.orderId()
                                + " was not cancelled. The request stays pending until it expires; /confirm reviews it again.");
                return;
            }
            if (answer.equals("y") || answer.equals("yes")) {
                out.println(Screens.outcome(confirmation.confirm(review, response)));
                if (id.equals(latestRequest)) {
                    latestRequest = null;
                }
                return;
            }
            out.println("Please answer y or n. Press Enter to keep the order.");
        }
    }
}
