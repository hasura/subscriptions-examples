import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import jakarta.websocket.*;

import java.io.IOException;

@ClientEndpoint(
        configurator = CustomClientEndpointConfigurator.class,
        subprotocols = {"graphql-transport-ws"}
)
public class GraphQLWebsocketClient {
    private Session session;
    private final GraphQLWSClientToServerMessage.ConnectionInit connectionInitPayload;

    private static final ObjectMapper mapper = new ObjectMapper().registerModule(new Jdk8Module());

    GraphQLWebsocketClient(GraphQLWSClientToServerMessage.ConnectionInit connectionInitPayload) {
        this.session = null;
        this.connectionInitPayload = connectionInitPayload;
    }

    public void sendMessage(GraphQLWSClientToServerMessage message) throws IOException {
        session.getBasicRemote().sendText(mapper.writeValueAsString(message));
    }

    @OnOpen
    public void onOpenCallback(Session session, EndpointConfig config) {
        this.session = session;
        try {
            session.getBasicRemote().sendText(mapper.writeValueAsString(connectionInitPayload));
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @OnMessage
    public void onMessageCallback(String message) {
        try {
            var messageObj = mapper.readValue(message, GraphQLWSMessage.class);
            System.out.println("========================");
            switch (messageObj) {
                case GraphQLWSServerToClientMessage serverMessage -> {
                    switch (serverMessage) {
                        case GraphQLWSServerToClientMessage.ConnectionAck connectionAck -> {
                            System.out.println("[JAKARTA EE] Connection ack message received: " + connectionAck);
                        }
                        case GraphQLWSServerToClientMessage.Next data -> {
                            System.out.println("[JAKARTA EE] Data message received: " + data);
                            System.out.println(mapper.writeValueAsString(data));
                        }
                        case GraphQLWSServerToClientMessage.Error error -> {
                            System.out.println("[JAKARTA EE] Error message received: " + error);
                            System.out.println(mapper.writeValueAsString(error));
                        }
                    }
                }
                case GraphQLWSBidirectionalMessage bidirectionalMessage -> {
                    switch (bidirectionalMessage) {
                        case GraphQLWSBidirectionalMessage.Ping ping -> {
                            System.out.println("[JAKARTA EE] Ping message received: " + ping);
                            System.out.println(mapper.writeValueAsString(ping));
                        }
                        case GraphQLWSBidirectionalMessage.Pong pong -> {
                            System.out.println("[JAKARTA EE] Pong message received: " + pong);
                            System.out.println(mapper.writeValueAsString(pong));
                        }
                        case GraphQLWSBidirectionalMessage.Complete complete -> {
                            System.out.println("[JAKARTA EE] Complete message received: " + complete);
                            System.out.println(mapper.writeValueAsString(complete));
                        }
                    }
                }
                default -> throw new IllegalStateException("Unexpected value: " + messageObj);
            }
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    @OnClose
    public void onCloseCallback(Session session, CloseReason closeReason) {
        System.out.println("[JAKARTA EE] Connection closed");
    }

    @OnError
    public void onErrorCallback(Session session, Throwable throwable) {
        System.out.println("[JAKARTA EE] Error occurred:" + throwable.getMessage());
    }
}
