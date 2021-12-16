import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.HandshakeResponse;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class CustomClientEndpointConfigurator extends ClientEndpointConfig.Configurator {

    @Override
    public void beforeRequest(Map<String, List<String>> headers) {
        String token = "test";
        headers.put("X-Hasura-Admin-Secret", Arrays.asList(token));
        super.beforeRequest(headers);
    }

    @Override
    public void afterResponse(HandshakeResponse hr) {
        System.out.println("Handshake Response Headers: " + hr.getHeaders());
    }
}
