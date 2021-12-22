# Examples of "graphql-transport-ws" protocol in Java

This folder contains examples of how to use "graphql-transport-ws" in Java.

Three examples are provided:

1. Using the native JDK `java.net.http.WebSocket` class, without any external dependencies
    - https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/WebSocket.html
2. Using the Vert.x `io.vertx.core.http.HttpClient` and `io.vertx.core.http.WebSocket` classes
    - https://vertx.io/docs/apidocs/io/vertx/core/http/WebSocket.html
3. Using the Enterprise Java/Jakarta EE `jakarta.websocket` specification
    - https://jakarta.ee/specifications/websocket/2.0/websocket-spec-2.0.html
    - https://jakarta.ee/specifications/websocket/2.0/apidocs/

To run the application, either:

- A) Open this folder in your Java-capable IDE (VS Code, IntelliJ, Netbeans, Eclipse, etc) and navigate to `/src/main/java/Application.java`, and press the "Run" icon to launch `Application.main()`
- B) Run the project using `Maven` from the command line
    - `$ mvn compile`
    - `$ MAVEN_OPTS="--enable-preview" mvn exec:java -Dexec.mainClass=Application`
