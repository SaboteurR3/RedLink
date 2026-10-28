package ge.tsu.redlink.missioncontrol.webhook;

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
 * A real HTTP server standing in for a customer's webhook endpoint. Records every call and answers
 * with scripted status codes (204 when the script is empty).
 */
class StubReceiver {

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Queue<Integer> script = new ConcurrentLinkedQueue<>();

    StubReceiver() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/hook", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
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
        var headers = exchange.getRequestHeaders();
        received.add(new Received(exchange.getRequestBody().readAllBytes(), headers.getFirst("X-RedLink-Signature"),
                headers.getFirst("X-RedLink-Event"), headers.getFirst("X-RedLink-Delivery"), headers.getFirst("User-Agent")));
        Integer status = script.poll();
        exchange.sendResponseHeaders(status == null ? 204 : status, -1);
        exchange.close();
    }

    record Received(byte[] body, String signature, String event, String deliveryId, String userAgent) {
    }
}
