package ge.tsu.redlink.missioncontrol.link;

import ge.tsu.redlink.contract.link.PacketSigner;
import ge.tsu.redlink.contract.link.SignatureVerificationException;
import ge.tsu.redlink.missioncontrol.web.ProblemResponses;
import ge.tsu.redlink.missioncontrol.web.ProblemTypes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Authenticates deep-space link packets. The signature covers the exact body bytes, so the body
 * is read and verified here, before anything parses it, and then handed on unchanged.
 *
 * <p>Not a Spring bean on purpose: Boot would register a bean filter for every URL.
 */
@Slf4j
@RequiredArgsConstructor
class LinkSignatureFilter extends OncePerRequestFilter {

    static final String LINK_ROLE = "LINK";
    private final PacketSigner signer;
    private final int maxPacketBytes;
    private final ProblemResponses problems;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxPacketBytes) {
            rejectTooLarge(request, response);
            return;
        }
        // Read one byte more than allowed to detect oversized bodies without a Content-Length.
        byte[] body = request.getInputStream().readNBytes(maxPacketBytes + 1);
        if (body.length > maxPacketBytes) {
            rejectTooLarge(request, response);
            return;
        }

        try {
            signer.verify(request.getHeader(PacketSigner.HEADER), body);
        } catch (SignatureVerificationException e) {
            // The reason is logged, never returned: a forger learns nothing about what was wrong.
            log.warn("Rejected link packet from {}: {} ({})", request.getRemoteAddr(), e.getReason(), e.getMessage());
            problems.write(request, response, HttpStatus.UNAUTHORIZED, ProblemTypes.INVALID_LINK_SIGNATURE,
                    "Invalid link signature", "The packet is not signed by a trusted link endpoint");
            return;
        }

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new PreAuthenticatedAuthenticationToken("deep-space-link", null,
                AuthorityUtils.createAuthorityList("ROLE_" + LINK_ROLE)));
        SecurityContextHolder.setContext(context);
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private void rejectTooLarge(HttpServletRequest request, HttpServletResponse response) throws IOException {
        problems.write(request, response, HttpStatus.CONTENT_TOO_LARGE, ProblemTypes.PACKET_TOO_LARGE,
                "Packet too large", "Link packets are limited to " + maxPacketBytes + " bytes");
    }
}
