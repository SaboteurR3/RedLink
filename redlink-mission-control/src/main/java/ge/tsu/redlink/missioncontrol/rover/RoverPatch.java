package ge.tsu.redlink.missioncontrol.rover;

import ge.tsu.redlink.missioncontrol.web.ApiExceptions.InvalidRequestException;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A JSON Merge Patch (RFC 7396) for a rover. Read from the raw JSON tree because merge patch gives
 * {@code null} a meaning ("remove this field"), which a plain DTO cannot tell apart from "absent".
 *
 * @param name new name, or empty to leave it unchanged
 */
record RoverPatch(Optional<String> name) {

    static final int MAX_NAME_LENGTH = 64;
    private static final String NAME = "name";

    static RoverPatch from(JsonNode json) {
        if (json == null || !json.isObject()) {
            throw new InvalidRequestException("A merge patch must be a JSON object", List.of());
        }
        List<String> errors = new ArrayList<>();
        for (Map.Entry<String, JsonNode> field : json.properties()) {
            if (!NAME.equals(field.getKey())) {
                errors.add(field.getKey() + ": cannot be changed");
            }
        }

        Optional<String> name = Optional.empty();
        JsonNode nameNode = json.get(NAME);
        if (nameNode != null) {
            if (nameNode.isNull()) {
                errors.add("name: cannot be removed");
            } else if (!nameNode.isString()) {
                errors.add("name: must be a string");
            } else if (nameNode.asString().isBlank()) {
                errors.add("name: must not be blank");
            } else if (nameNode.asString().strip().length() > MAX_NAME_LENGTH) {
                errors.add("name: must be at most " + MAX_NAME_LENGTH + " characters");
            } else {
                name = Optional.of(nameNode.asString().strip());
            }
        }
        if (!errors.isEmpty()) {
            throw new InvalidRequestException("The merge patch is invalid", errors.stream().sorted().toList());
        }
        return new RoverPatch(name);
    }
}
