import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.WebsocketVersion;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.WebSocketContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;


record GraphQLError(String message, List<GraphQLErrorSourceLocation> locations, String path, String extensions) {
    record GraphQLErrorSourceLocation(int line, int column) {
    }
}

record GraphQLExecutionResult(List<GraphQLError> errors, Map<String, Object> data) {
}

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(name = "ping", value = GraphQLWSBidirectionalMessage.Ping.class),
        @JsonSubTypes.Type(name = "pong", value = GraphQLWSBidirectionalMessage.Pong.class),
        @JsonSubTypes.Type(name = "complete", value = GraphQLWSBidirectionalMessage.Complete.class),
        @JsonSubTypes.Type(name = "connection_init", value = GraphQLWSClientToServerMessage.ConnectionInit.class),
        @JsonSubTypes.Type(name = "subscribe", value = GraphQLWSClientToServerMessage.Subscribe.class),
        @JsonSubTypes.Type(name = "connection_ack", value = GraphQLWSServerToClientMessage.ConnectionAck.class),
        @JsonSubTypes.Type(name = "error", value = GraphQLWSServerToClientMessage.Error.class),
        @JsonSubTypes.Type(name = "next", value = GraphQLWSServerToClientMessage.Next.class),
})
sealed interface GraphQLWSMessage permits GraphQLWSBidirectionalMessage, GraphQLWSClientToServerMessage, GraphQLWSServerToClientMessage {
    String getType();
}

sealed interface GraphQLWSBidirectionalMessage extends GraphQLWSMessage {
    /**
     * Direction: bidirectional
     * <p>
     * Useful for detecting failed connections, displaying latency metrics or other types of network probing.
     * <p>
     * A Pong must be sent in response from the receiving party as soon as possible.
     * <p>
     * The Ping message can be sent at any time within the established socket.
     * <p>
     * The optional payload field can be used to transfer additional details about the ping.
     */
    record Ping(Optional<Map<String, Object>> payload) implements GraphQLWSBidirectionalMessage {
        @Override
        @JsonGetter("type")
        public String getType() {
            return "ping";
        }
    }

    /**
     * Direction: bidirectional
     * <p>
     * The response to the Ping message. Must be sent as soon as the Ping message is received.
     * <p>
     * The Pong message can be sent at any time within the established socket. Furthermore, the Pong message may even be sent unsolicited as an unidirectional heartbeat.
     * <p>
     * The optional payload field can be used to transfer additional details about the pong.
     */
    record Pong(Optional<Map<String, Object>> payload) implements GraphQLWSBidirectionalMessage {
        @Override
        @JsonGetter("type")
        public String getType() {
            return "pong";
        }
    }

    /**
     * Direction: bidirectional
     * <p>
     * Server -> Client indicates that the requested operation execution has completed. If the server dispatched the Error message relative to the original Subscribe message, no Complete message will be emitted.
     * <p>
     * Client -> Server indicates that the client has stopped listening and wants to complete the subscription. No further events, relevant to the original subscription, should be sent through. Even if the client completed a single result operation before it resolved, the result should not be sent through once it does.
     */
    record Complete(String id) implements GraphQLWSBidirectionalMessage {
        @Override
        @JsonGetter("type")
        public String getType() {
            return "complete";
        }
    }
}

sealed interface GraphQLWSClientToServerMessage extends GraphQLWSMessage {
    /**
     * Direction: Client -> Server
     * <p>
     * Indicates that the client wants to establish a connection within the existing socket. This connection is not the actual WebSocket communication channel, but is rather a frame within it asking the server to allow future operation requests.
     * <p>
     * The server must receive the connection initialisation message within the allowed waiting time specified in the connectionInitWaitTimeout parameter during the server setup. If the client does not request a connection within the allowed timeout, the server will close the socket with the event: 4408: Connection initialisation timeout.
     * <p>
     * If the server receives more than one ConnectionInit message at any given time, the server will close the socket with the event 4429: Too many initialisation requests.
     */
    record ConnectionInit(Optional<Map<String, Object>> payload) implements GraphQLWSClientToServerMessage {
        @Override
        @JsonGetter("type")
        public String getType() {
            return "connection_init";
        }
    }

    /**
     * Direction: Client -> Server
     * <p>
     * Requests an operation specified in the message payload. This message provides a unique ID field to connect published messages to the operation requested by this message.
     * <p>
     * If there is already an active subscriber for an operation matching the provided ID, regardless of the operation type, the server must close the socket immediately with the event 4409: Subscriber for <unique-operation-id> already exists.
     * <p>
     * Executing operations is allowed only after the server has acknowledged the connection through the ConnectionAck message, if the connection is not acknowledged, the socket will be closed immediately with the event 4401: Unauthorized.
     */
    record Subscribe(String id, SubscribePayload payload) implements GraphQLWSClientToServerMessage {

        record SubscribePayload(Optional<String> operationName, String query, Optional<Map<String, Object>> variables,
                                Optional<Map<String, Object>> extensions) {
        }

        @Override
        @JsonGetter("type")
        public String getType() {
            return "subscribe";
        }
    }
}

sealed interface GraphQLWSServerToClientMessage extends GraphQLWSMessage {
    /**
     * Direction: Server -> Client
     * <p>
     * Expected response to the ConnectionInit message from the client acknowledging a successful connection with the server.
     * <p>
     * The server can use the optional payload field to transfer additional details about the connection.
     */
    record ConnectionAck(Optional<Map<String, Object>> payload) implements GraphQLWSServerToClientMessage {
        @Override
        @JsonGetter("type")
        public String getType() {
            return "connection_ack";
        }
    }

    /**
     * Direction: Server -> Client
     * <p>
     * Operation execution result(s) from the source stream created by the binding Subscribe message. After all results have been emitted, the Complete message will follow indicating stream completion.
     */
    record Next(String id, GraphQLExecutionResult payload) implements GraphQLWSServerToClientMessage {
        @Override
        @JsonGetter("type")
        public String getType() {
            return "next";
        }
    }

    /**
     * Direction: Server -> Client
     * <p>
     * Operation execution error(s) triggered by the Next message happening before the actual execution, usually due to validation errors.
     */
    record Error(String id, List<GraphQLError> payload) implements GraphQLWSServerToClientMessage {
        @Override
        @JsonGetter("type")
        public String getType() {
            return "error";
        }
    }

}


public class Application {

    // Uncomment one of the below to run an example
    public static void main(String[] args) {
        Application app = new Application();
        // app.vertxTest();
        // app.jakartaEETest();
        app.testVanillaJDKWebsocket();
    }

    private static final String url = "ws://localhost:8080/v1/graphql";

    private static final String subscription = """
                   subscription MySubscription2 {
                     user {
                       name
                       updated_at
                       created_at
                       id
                       age
                     }
                   }
            """;

    // Example of a graphql-transport-ws client using the vanilla JDK websocket implementation
    public void testVanillaJDKWebsocket() {
        System.out.println("Starting vanilla JDK websocket test");
        ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());

        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        WebSocket websocket = client.newWebSocketBuilder()
                .subprotocols("graphql-transport-ws")
                .buildAsync(URI.create(url), new WebSocket.Listener() {
                    StringBuilder text = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        System.out.println("Opened");
                        // You MUST call super.onOpen() or else no data will be sent
                        WebSocket.Listener.super.onOpen(webSocket);
                        try {
                            webSocket.sendText(
                                    mapper.writeValueAsString(new GraphQLWSClientToServerMessage.ConnectionInit(Optional.empty())),
                                    true
                            );
                        } catch (JsonProcessingException e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket,
                                                     CharSequence message,
                                                     boolean last) {
                        text.append(message);
                        if (last) {
                            processWholeText(text);
                            text = new StringBuilder();
                        }
                        webSocket.request(1);
                        return null;
                    }

                    private void processWholeText(CharSequence parts) {
                        String wholeText = parts.toString();
                        System.out.println("wholeText: " + wholeText);
                        try {
                            GraphQLWSMessage message = mapper.readValue(wholeText, GraphQLWSMessage.class);
                            handleGraphQLMessage(message);
                        } catch (JsonProcessingException e) {
                            e.printStackTrace();
                        }
                    }

                    private void handleGraphQLMessage(GraphQLWSMessage message) {
                        switch (message) {
                            case GraphQLWSBidirectionalMessage.Ping ping -> {
                                System.out.println("[VANILLA] Ping received");
                                System.out.println(ping);
                            }
                            case GraphQLWSBidirectionalMessage.Complete complete -> {
                                System.out.println("[VANILLA] Complete received");
                                System.out.println(complete);
                            }
                            case GraphQLWSServerToClientMessage.ConnectionAck connectionAck -> {
                                System.out.println("[VANILLA] ConnectionAck received");
                                System.out.println(connectionAck);
                            }
                            case GraphQLWSServerToClientMessage.Error error -> {
                                System.out.println("[VANILLA] Error received");
                                System.out.println(error);
                            }
                            case GraphQLWSServerToClientMessage.Next data -> {
                                System.out.println("[VANILLA] Data received");
                                System.out.println(data);
                            }
                            default -> throw new IllegalStateException("Unexpected value: " + message);
                        }
                    }
                }).join();

        try {
            websocket.sendText(
                    mapper.writeValueAsString(
                            new GraphQLWSClientToServerMessage.Subscribe(
                                    UUID.randomUUID().toString(),
                                    new GraphQLWSClientToServerMessage.Subscribe.SubscribePayload(
                                            Optional.empty(),
                                            subscription,
                                            Optional.empty(),
                                            Optional.empty()
                                    )
                            )
                    ), true);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }

        while (!websocket.isInputClosed()) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

    }

    // Example of a graphql-transport-ws client using Jakarta EE WebSocket API
    public void jakartaEETest() {
        final WebSocketContainer webSocketContainer = ContainerProvider.getWebSocketContainer();
        try {
            var client = new GraphQLWebsocketClient(new GraphQLWSClientToServerMessage.ConnectionInit(Optional.empty()));
            var session = webSocketContainer.connectToServer(client, URI.create(url));
            client.sendMessage(
                    new GraphQLWSClientToServerMessage.Subscribe(
                            UUID.randomUUID().toString(),
                            new GraphQLWSClientToServerMessage.Subscribe.SubscribePayload(
                                    Optional.empty(),
                                    subscription,
                                    Optional.empty(),
                                    Optional.empty()
                            )
                    )
            );
        } catch (DeploymentException | IOException e) {
            e.printStackTrace();
        }
    }

    // Implementation of a graphql-transport-ws client using Vert.x HTTP Client + WebSocket API
    public void vertxTest() {
        Vertx vertx = Vertx.vertx();
        ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());

        HttpClient client = vertx.createHttpClient();
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        headers.add("X-Hasura-Admin-Secret", "my-secret");

        AtomicBoolean hasSentConnectionInit = new AtomicBoolean(false);

        var conn = client.webSocketAbs(url, headers, WebsocketVersion.V13, List.of("graphql-transport-ws"));
        conn
                .onFailure(t -> System.out.println("[VERTX] Failure: " + t.getMessage()))
                .onSuccess(ctx -> {
                    // Send CONN_INIT message (only once) to initialize graphql-transport-ws connection
                    if (!hasSentConnectionInit.get()) {
                        try {
                            ctx.writeTextMessage(mapper.writeValueAsString(new GraphQLWSClientToServerMessage.ConnectionInit(Optional.empty())));
                            hasSentConnectionInit.set(true);
                        } catch (JsonProcessingException e) {
                            e.printStackTrace();
                        }
                    }

                    ctx.exceptionHandler(e -> System.out.println("[VERTX] Exception: " + e.getMessage()));
                    ctx.closeHandler(__ -> System.out.println("[VERTX] Closed"));
                    ctx.endHandler(__ -> System.out.println("[VERTX] End"));

                    // Send SUBSCRIBE message to subscribe to the user query
                    try {
                        ctx.writeTextMessage(mapper.writeValueAsString(
                                new GraphQLWSClientToServerMessage.Subscribe(
                                        UUID.randomUUID().toString(),
                                        new GraphQLWSClientToServerMessage.Subscribe.SubscribePayload(
                                                Optional.empty(),
                                                subscription,
                                                Optional.empty(),
                                                Optional.empty()
                                        )
                                )
                        ));
                    } catch (JsonProcessingException e) {
                        e.printStackTrace();
                    }

                    // Read messages from the server
                    ctx.textMessageHandler((msg) -> {
                        try {
                            GraphQLWSMessage message = mapper.readValue(msg, GraphQLWSMessage.class);
                            System.out.println("[VERTX] Deserialized message: " + message);
                            switch (message) {
                                case GraphQLWSBidirectionalMessage.Ping ping -> {
                                    System.out.println("[VERTX] Ping received");
                                }
                                case GraphQLWSBidirectionalMessage.Complete complete -> {
                                    System.out.println("[VERTX] Complete message received");
                                }
                                case GraphQLWSServerToClientMessage.ConnectionAck connectionAck -> {
                                    System.out.println("[VERTX] ConnectionAck received, sending subscribe message");
                                }
                                case GraphQLWSServerToClientMessage.Error error -> {
                                    System.out.println("[VERTX] Error received");
                                }
                                case GraphQLWSServerToClientMessage.Next data -> {
                                    System.out.println("[VERTX] Data received");
                                    System.out.println(data);
                                }
                                // Somehow received a message that is not bidirectional or server to client
                                default -> throw new IllegalStateException("[VERTX] Unexpected value: " + message);
                            }
                        } catch (JsonProcessingException e) {
                            e.printStackTrace();
                        }
                    });
                });

    }
}
