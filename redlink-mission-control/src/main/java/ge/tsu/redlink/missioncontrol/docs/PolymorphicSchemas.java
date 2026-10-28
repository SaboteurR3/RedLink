package ge.tsu.redlink.missioncontrol.docs;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import ge.tsu.redlink.contract.command.RoverCommand;
import ge.tsu.redlink.contract.telemetry.TelemetryPayload;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Makes the OpenAPI discriminators of the contract's polymorphic types match the wire format.
 *
 * <p>springdoc emits {@code discriminator: {propertyName: type}} without a mapping, and OpenAPI
 * then defaults each subtype's discriminator value to its schema name ({@code "Move"}), while the
 * API sends and expects the {@code @JsonSubTypes} names ({@code "MOVE"}). Clients generated from
 * the document would send the wrong type. The mapping is built from the same annotations Jackson
 * uses, so it cannot drift; the contract module stays free of Swagger dependencies.
 */
@Component
class PolymorphicSchemas implements OpenApiCustomizer {

    private static final List<Class<?>> POLYMORPHIC_BASES = List.of(RoverCommand.class, TelemetryPayload.class);

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
            return;
        }
        Map<String, Schema> schemas = openApi.getComponents().getSchemas();
        for (Class<?> base : POLYMORPHIC_BASES) {
            Schema<?> baseSchema = schemas.get(base.getSimpleName());
            JsonSubTypes subTypes = base.getAnnotation(JsonSubTypes.class);
            if (baseSchema == null || subTypes == null) {
                continue;
            }
            Discriminator discriminator = baseSchema.getDiscriminator() != null
                    ? baseSchema.getDiscriminator() : new Discriminator().propertyName("type");
            for (JsonSubTypes.Type subType : subTypes.value()) {
                discriminator.mapping(subType.name(), "#/components/schemas/" + subType.value().getSimpleName());
            }
            baseSchema.setDiscriminator(discriminator);
        }
    }
}
