package ge.tsu.redlink.missioncontrol.docs;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document at {@code /v3/api-docs} (Swagger UI: {@code /swagger-ui.html}). Every
 * operation requires a bearer token unless it opts out (login).
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    static final String BEARER = "bearer";

    @Bean
    OpenAPI redLinkOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("RedLink Mission Control API")
                        .version("v1")
                        .description("""
                                Command a simulated Mars rover over a delayed deep-space link.
                                Get a token from POST /api/v1/auth/token, then use "Authorize".
                                Errors are RFC 9457 Problem Details (application/problem+json)."""))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
