package ge.tsu.redlink.missioncontrol.web;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.exc.InvalidTypeIdException;
import tools.jackson.databind.exc.MismatchedInputException;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns a JSON binding failure into one line a client can act on: which field, and what is allowed
 * there (e.g. {@code "steps[0].heading: must be one of NORTH, EAST, SOUTH, WEST"}). Only field names
 * from our own API and the allowed values are shown; the client's value is echoed only for unknown
 * type ids, and only if it is a short plain word.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
final class ReadableErrors {

    private static final Pattern PLAIN_WORD = Pattern.compile("[A-Za-z0-9_.-]{1,40}");

    static List<String> of(Throwable cause) {
        if (cause instanceof InvalidTypeIdException e) {
            String field = path(e).isEmpty() ? "type" : path(e) + ".type";
            String allowed = allowedTypeIds(e.getBaseType().getRawClass());
            String given = e.getTypeId() == null || e.getTypeId().isBlank() ? null : e.getTypeId();
            if (given == null) {
                return List.of(field + ": is missing" + (allowed.isEmpty() ? "" : "; one of " + allowed));
            }
            String shown = PLAIN_WORD.matcher(given).matches() ? "'" + given + "' " : "";
            return List.of(field + ": " + shown + "is not one of " + allowed);
        }
        if (cause instanceof MismatchedInputException e && !path(e).isEmpty()) {
            Class<?> target = e.getTargetType();
            return List.of(path(e) + ": " + expectation(target));
        }
        return List.of();
    }

    private static String expectation(Class<?> target) {
        if (target == null) {
            return "has the wrong type";
        }
        if (target.isEnum()) {
            return "must be one of " + Arrays.stream(target.getEnumConstants()).map(Object::toString)
                    .collect(Collectors.joining(", "));
        }
        if (target == int.class || target == Integer.class || target == long.class || target == Long.class) {
            return "must be a whole number";
        }
        if (target == double.class || target == Double.class || target == float.class || target == Float.class) {
            return "must be a number";
        }
        if (target == boolean.class || target == Boolean.class) {
            return "must be true or false";
        }
        if (target == String.class) {
            return "must be a string";
        }
        return "has the wrong type";
    }

    /** e.g. {@code steps[2].heading}; empty for the root. */
    private static String path(MismatchedInputException e) {
        StringBuilder path = new StringBuilder();
        for (JacksonException.Reference reference : e.getPath()) {
            if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            } else if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            }
        }
        return path.toString();
    }

    private static String allowedTypeIds(Class<?> base) {
        JsonSubTypes subTypes = base == null ? null : base.getAnnotation(JsonSubTypes.class);
        if (subTypes == null) {
            return "";
        }
        return Arrays.stream(subTypes.value()).map(JsonSubTypes.Type::name).collect(Collectors.joining(", "));
    }
}
