package ge.tsu.redlink.missioncontrol.auth;

import ge.tsu.redlink.missioncontrol.auth.AuthDtos.MeResponse;
import ge.tsu.redlink.missioncontrol.auth.AuthDtos.TokenRequest;
import ge.tsu.redlink.missioncontrol.auth.AuthDtos.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * Username and password in, access token out. Public endpoint.
     */
    @Operation(summary = "Log in: username and password in, bearer token out")
    @SecurityRequirements // public: overrides the global bearer requirement in the OpenAPI document
    @PostMapping("/token")
    ResponseEntity<TokenResponse> token(@Valid @RequestBody TokenRequest request, HttpServletRequest http) {
        TokenService.IssuedToken token = authService.login(request.username(), request.password(), http.getRemoteAddr());
        // RFC 6749 §5.1: responses containing tokens must not be cached.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(new TokenResponse(token.value(), "Bearer", token.expiresIn().toSeconds()));
    }

    /**
     * Who the token belongs to. Lets the frontend show the user and hide actions their role
     * cannot perform (the server still checks every request).
     */
    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        return new MeResponse(jwt.getSubject(), jwt.getClaimAsStringList(TokenService.ROLES_CLAIM), jwt.getExpiresAt());
    }
}
