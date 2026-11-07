package com.bazlur.mrs.cli;

import static org.junit.jupiter.api.Assertions.*;

import module java.base;

import com.bazlur.mrs.agent.OrderAgent;
import com.bazlur.mrs.domain.OrderAccess;
import com.bazlur.mrs.support.Json;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class InteractiveShellTest {
    static final String REQUEST_ID = "066631b6-da7a-11f1-a146-f25ecc3dad45";

    record Session(String text, List<String> prompts) {}

    static final OrderAccess ORDERS =
            new OrderAccess() {
                public JsonNode listOrders() {
                    return Json.parse("{\"orders\":[]}");
                }

                public JsonNode getOrder(long id) {
                    return Json.parse("{\"ok\":true,\"document\":{\"_id\":" + id + "}}");
                }

                public JsonNode requestCancellation(long id, String reason, UUID key) {
                    return Json.parse(
                            "{\"ok\":true,\"state\":\"PENDING\",\"orderId\":"
                                    + id
                                    + ",\"requestId\":\""
                                    + REQUEST_ID
                                    + "\"}");
                }
            };

    static ChatModel scripted(AiMessage... replies) {
        var queue = new ArrayDeque<>(List.of(replies));
        return new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(queue.removeFirst()).build();
            }
        };
    }

    static AiMessage toolCall(String name, String arguments) {
        return AiMessage.from(
                ToolExecutionRequest.builder().id("1").name(name).arguments(arguments).build());
    }

    static Session run(OrderAgent agent, String... input) {
        var lines = new ArrayDeque<>(List.of(input));
        var prompts = new ArrayList<String>();
        var text = new StringWriter();
        new InteractiveShell(
                        null,
                        agent,
                        null,
                        prompt -> {
                            prompts.add(prompt);
                            assertFalse(lines.isEmpty(), "The session must end before input does");
                            return lines.removeFirst();
                        },
                        new PrintWriter(text, true))
                .run();
        assertTrue(lines.isEmpty());
        return new Session(text.toString(), prompts);
    }

    /** Any accidental model call or REST dispatch fails instead of using a live service. */
    static OrderAgent noModel() {
        return new OrderAgent(new ChatModel() {}, null);
    }

    @Test
    void slashCommandsAndMisplacedApprovalsNeverReachTheModel() {
        var session =
                run(
                        noModel(),
                        "/help",
                        "/",
                        "/review invalid-uuid",
                        "/unknown",
                        "/confirm",
                        "yes",
                        "CONFIRM " + UUID.randomUUID(),
                        "/clear",
                        "/quit");
        String text = session.text();
        assertTrue(text.contains("Invalid command or arguments"), text);
        assertTrue(text.contains("Enter /confirm to review a request first"), text);
        assertTrue(text.contains("Conversation cleared"), text);
        assertFalse(text.contains("did not work"), text);
    }

    @Test
    void bareCommandWordsStayLocal() {
        String text = run(noModel(), "help", "?", "HELP", "clear", "exit").text();
        assertEquals(3, text.split("Commands:", -1).length - 1, text);
        assertTrue(text.contains("Conversation cleared"), text);
        assertFalse(text.contains("Assistant:"), text);
        assertFalse(text.contains("did not work"), text);
    }

    @Test
    void quitAndExitBothEndTheSession() {
        run(noModel(), "/quit");
        run(noModel(), "/exit");
        run(noModel(), "quit");
    }

    @Test
    void promptIsOfferedInlineAndOutputHasNoLogLevels() {
        var session = run(noModel(), "/help", "/quit");
        assertEquals(List.of("> ", "> "), session.prompts());
        assertFalse(session.text().contains("INFO"), session.text());
        assertFalse(session.text().contains(">\n"), session.text());
    }

    @Test
    void invalidCommandsPointToHelpWithoutAUsageDump() {
        String text = run(noModel(), "/unknown", "/quit").text();
        assertTrue(text.contains("/help"), text);
        assertFalse(text.contains("Usage:"), text);
    }

    @Test
    void flagsTypedAtThePromptNeverReachTheModel() {
        String text =
                run(
                                noModel(),
                                "-v",
                                "-V",
                                "--version",
                                "version",
                                "/version",
                                "-h",
                                "--help",
                                "-x",
                                "/quit")
                        .text();
        assertEquals(5, text.split(OrderCommand.VERSION, -1).length - 1, text);
        assertEquals(2, text.split("Commands:", -1).length - 1, text);
        assertTrue(text.contains("Commands start with /"), text);
        assertFalse(text.contains("did not work"), text);
    }

    @Test
    void sentenceStartingWithADashIsStillAQuestion() {
        // This agent fails on any model call, so the failure proves the text was sent.
        assertTrue(run(noModel(), "- is order 1001 late", "/quit").text().contains("did not work"));
    }

    @Test
    void commandSyntaxHelpStaysAvailable() {
        String text = run(noModel(), "/confirm --help", "/help review", "/quit").text();
        assertTrue(text.contains("confirm [-hV] [REQUEST_ID]"), text);
        assertTrue(text.contains("review [-hV] [REQUEST_ID]"), text);
    }

    @Test
    void endOfInputEndsTheSessionCleanly() {
        var text = new StringWriter();
        new InteractiveShell(null, noModel(), null, prompt -> null, new PrintWriter(text, true))
                .run();
        assertTrue(text.toString().endsWith(System.lineSeparator()), text.toString());
    }

    @Test
    void modelTextAndRecordedRequestStaySeparate() {
        var agent =
                new OrderAgent(
                        scripted(
                                toolCall(
                                        "requestCancellation",
                                        "{\"orderId\":1003,\"reason\":\"Not stated\"}"),
                                AiMessage.from("Your order was cancelled.")),
                        ORDERS);
        String text = run(agent, "cancel order 1003", "/quit").text();
        assertTrue(text.contains("Assistant: Your order was cancelled."), text);
        assertTrue(text.contains("Request recorded. Order 1003 is not cancelled yet."), text);
        assertTrue(text.contains(REQUEST_ID), text);
        assertFalse(text.contains("{\"ok\""), text);
    }

    @Test
    void withoutAnExecutorTheSessionSaysApprovalsAreOff() {
        assertTrue(run(noModel(), "/quit").text().contains("MRS_EXECUTOR_PASSWORD"));
        String text = run(noModel(), "/quit").text();
        String afterConfirm = run(noModel(), "/confirm", "/quit").text().substring(text.length());
        assertTrue(afterConfirm.contains("MRS_EXECUTOR_PASSWORD"), afterConfirm);
    }

    @Test
    void recordedRequestHintMatchesWhetherApprovalIsAvailable() {
        var agent =
                new OrderAgent(
                        scripted(
                                toolCall(
                                        "requestCancellation",
                                        "{\"orderId\":1003,\"reason\":\"Not stated\"}"),
                                AiMessage.from("Requested.")),
                        ORDERS);
        String text = run(agent, "cancel order 1003", "/quit").text();
        assertFalse(text.contains("Type /confirm"), text);
    }

    @Test
    void answerWithoutACheckedOrderExplainsWhatToDo() {
        var agent = new OrderAgent(scripted(AiMessage.from("Hello there!")), ORDERS);
        String text = run(agent, "hi", "/quit").text();
        assertTrue(text.contains("not showing the answer"), text);
        assertFalse(text.contains("Hello there"), text);
        assertFalse(text.contains("Exception"), text);
    }

    @Test
    void oversizedQuestionIsRefusedWithAClearLimit() {
        String text = run(noModel(), "x".repeat(2001), "/quit").text();
        assertTrue(text.contains("2000"), text);
        assertFalse(text.contains("Exception"), text);
    }
}
