package ge.tsu.redlink.roversim.downlink;

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
 * A real HTTP server standing in for mission-control's {@code /dsn/downlink}. Records what it
 * receives and answers with scripted status codes (202 when the script is empty).
 */
class StubEarth {

    /** Close the connection without answering, like a crashed or unreachable Earth. */
    static final int DROP_CONNECTION = -1;

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final Queue<Reply> script = new ConcurrentLinkedQueue<>();

    StubEarth() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/dsn/downlink", this::handle);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void respondWith(Integer... statuses) {
        for (Integer status : statuses) {
            script.add(new Reply(status, null));
        }
    }

    /** One answer with a {@code Retry-After} header. */
    void respondWith(int status, String retryAfter) {
        script.add(new Reply(status, retryAfter));
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
                exchange.getRequestHeaders().getFirst("Upgrade")));
        Reply reply = script.poll();
        if (reply != null && reply.status() == DROP_CONNECTION) {
            exchange.close();
            return;
        }
        if (reply != null && reply.retryAfter() != null) {
            exchange.getResponseHeaders().add("Retry-After", reply.retryAfter());
        }
        exchange.sendResponseHeaders(reply == null ? 202 : reply.status(), -1);
        exchange.close();
    }

    private record Reply(int status, String retryAfter) {
    }

    /** @param upgrade the Upgrade header; Tomcat breaks on "h2c" upgrades of POSTs, so it must be absent */
    record Received(byte[] body, String signature, String upgrade) {
    }
}
