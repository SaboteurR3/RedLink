package ge.tsu.redlink.roversim.tracing;

import ge.tsu.redlink.contract.link.TraceContext;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Carries a trace across the link, where HTTP alone cannot: a command is stored, transmitted
 * minutes later by a scheduler, executed on Mars and reported back by another scheduler. The W3C
 * {@code traceparent} is kept with the command and inside each packet, and work done later
 * continues that trace in a new span.
 *
 * <p>Without tracing (no {@link Tracer} bean) everything still works, untraced.
 */
@Component
public class LinkTracing {

    public static final String HEADER = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    public LinkTracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this.tracer = tracer.getIfAvailable(() -> Tracer.NOOP);
        this.propagator = propagator.getIfAvailable(() -> Propagator.NOOP);
    }

    /** The current trace as a W3C traceparent, or null when nothing is traced right now. */
    public String currentTraceparent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        String traceparent = carrier.get(HEADER);
        return traceparent != null && traceparent.matches(TraceContext.TRACEPARENT_PATTERN) ? traceparent : null;
    }

    /**
     * Runs {@code work} in a new span named {@code name}: a child of {@code traceparent} when it is
     * given, otherwise the start of a new trace.
     */
    public <T> T inSpan(String traceparent, String name, Supplier<T> work) {
        Span.Builder builder = traceparent == null
                ? tracer.spanBuilder()
                : propagator.extract(Map.of(HEADER, traceparent), Map::get);
        Span span = builder.name(name).start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return work.get();
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }

    public void inSpan(String traceparent, String name, Runnable work) {
        inSpan(traceparent, name, () -> {
            work.run();
            return null;
        });
    }
}
