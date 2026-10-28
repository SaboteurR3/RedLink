package ge.tsu.redlink.missioncontrol.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 8288 web linking for every paged list: {@code Link: <…?page=0&size=20>; rel="first", …} with
 * {@code first}, {@code prev}, {@code next} and {@code last}, so a client can walk the pages without
 * building URLs. The targets are the request's own path and query with only {@code page} changed,
 * relative like the {@code _links} in the bodies.
 */
@RestControllerAdvice
class PageLinks implements ResponseBodyAdvice<PagedModel<?>> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return PagedModel.class.isAssignableFrom(returnType.getParameterType());
    }

    @Override
    public PagedModel<?> beforeBodyWrite(PagedModel<?> body, MethodParameter returnType, MediaType contentType,
                                         Class<? extends HttpMessageConverter<?>> converterType,
                                         ServerHttpRequest request, ServerHttpResponse response) {
        if (body != null && body.getMetadata() != null && request instanceof ServletServerHttpRequest servlet) {
            String header;
            try {
                header = header(servlet.getServletRequest(), body.getMetadata().number(), body.getMetadata().totalPages());
            } catch (IllegalArgumentException e) {
                return body; // a query Tomcat let through but URI rules reject: links are a convenience, skip them
            }
            if (!header.isEmpty()) {
                response.getHeaders().add(HttpHeaders.LINK, header);
            }
        }
        return body;
    }

    static String header(HttpServletRequest request, long page, long totalPages) {
        String query = request.getQueryString();
        UriComponentsBuilder self = UriComponentsBuilder.fromUriString(
                request.getRequestURI() + (query == null ? "" : "?" + query));
        List<String> links = new ArrayList<>();
        long last = Math.max(0, totalPages - 1);
        links.add(link(self, 0, "first"));
        if (page > 0) {
            links.add(link(self, Math.min(page - 1, last), "prev"));
        }
        if (page < last) {
            links.add(link(self, page + 1, "next"));
        }
        links.add(link(self, last, "last"));
        return String.join(", ", links);
    }

    private static String link(UriComponentsBuilder self, long page, String rel) {
        return "<" + self.cloneBuilder().replaceQueryParam("page", page).build(true).toUriString() + ">; rel=\"" + rel + "\"";
    }
}
