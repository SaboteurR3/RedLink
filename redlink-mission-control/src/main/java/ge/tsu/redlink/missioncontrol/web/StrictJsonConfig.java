package ge.tsu.redlink.missioncontrol.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.type.LogicalType;

/**
 * No silent guessing with commands: Jackson's defaults would turn {@code "distanceMeters": 1.5} into
 * a 1 m drive, accept {@code "3"} for 3 and {@code "heading": 1} for EAST. A rover command must say
 * exactly what it means, so these are 400s with the field named (see {@link ApiProblemHandler}).
 */
@Configuration
class StrictJsonConfig {

    @Bean
    JsonMapperBuilderCustomizer strictScalars() {
        return builder -> builder
                .withCoercionConfig(LogicalType.Integer, config -> config
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Float, config -> config
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail))
                .withCoercionConfig(LogicalType.Boolean, config -> config
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail))
                .enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
    }
}
