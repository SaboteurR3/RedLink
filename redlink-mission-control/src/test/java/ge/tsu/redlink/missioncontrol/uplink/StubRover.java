package ge.tsu.redlink.missioncontrol.uplink;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A real HTTP server standing in for rover-sim's {@code /flight/uplink}. Records what it receives
 * and answers with scripted status codes (202 when the script is empty).
 */
class StubRover {

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Queue<Integer> script = new ConcurrentLinkedQueue<>();

    StubRover() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/flight/uplink", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void respondWith(Integer... statuses) {
        script.addAll(List.of(statuses));
    }

    List<Received> received() {
        return received;
    }

    void reset() {
        received.clear();
        script.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        received.add(new Received(body, exchange.getRequestHeaders().getFirst("X-Link-Signature"),
                exchange.getRequestHeaders().getFirst("Upgrade"), exchange.getRequestHeaders().getFirst("traceparent")));
        Integer status = script.poll();
        exchange.sendResponseHeaders(status == null ? 202 : status, -1);
        exchange.close();
    }

    /**
     * @param upgrade     the Upgrade header; must be absent (Tomcat cannot upgrade a POST with a body)
     * @param traceparent the W3C trace header, if any
     */
    record Received(byte[] body, String signature, String upgrade, String traceparent) {
    }
}
