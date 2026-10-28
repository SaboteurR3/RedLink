package ge.tsu.redlink.missioncontrol.web;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity tags (RFC 9110 §8.8.3) for conditional requests.
 *
 * <p>{@code If-None-Match} on GET is handled by Spring (it turns a 200 with a matching {@code ETag}
 * into 304). {@code If-Match} on writes is handled here, because the comparison has to happen
 * inside the same transaction as the update.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class EntityTags {

    /** A strong entity tag for a version number, e.g. {@code "7"}. */
    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /**
     * RFC 9110 §13.1.1: {@code *} matches any current representation; otherwise one of the listed
     * tags must equal {@code currentTag} by strong comparison, so weak tags ({@code W/"7"}) never match.
     */
    public static boolean ifMatchSatisfied(String ifMatchHeader, String currentTag) {
        if (ifMatchHeader.trim().equals("*")) {
            return true;
        }
        return parse(ifMatchHeader).stream()
                .filter(tag -> !tag.startsWith("W/"))
                .anyMatch(currentTag::equals);
    }

    /** Splits a list of entity tags; commas inside quotes belong to the tag. */
    static List<String> parse(String header) {
        List<String> tags = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (char c : header.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == ',' && !quoted) {
                addIfPresent(tags, current);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        addIfPresent(tags, current);
        return tags;
    }

    private static void addIfPresent(List<String> tags, StringBuilder tag) {
        String trimmed = tag.toString().trim();
        if (!trimmed.isEmpty()) {
            tags.add(trimmed);
        }
    }
}
