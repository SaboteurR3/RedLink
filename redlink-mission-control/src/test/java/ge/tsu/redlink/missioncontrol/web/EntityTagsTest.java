package ge.tsu.redlink.missioncontrol.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class EntityTagsTest {

    @Test
    void versionBecomesAQuotedStrongTag() {
        assertThat(EntityTags.of(7)).isEqualTo("\"7\"");
    }

    @ParameterizedTest(name = "If-Match: {0} vs \"7\" → {1}")
    @CsvSource(delimiter = '|', value = {
            "\"7\"              | true",
            "\"6\"              | false",
            "*                  | true",
            "\"5\", \"7\"       | true",
            "\"5\",\"6\"        | false",
            "W/\"7\"            | false",   // weak tags never match If-Match (strong comparison)
            "\"7,8\"            | false",   // a comma inside quotes belongs to the tag
            "  \"7\"            | true"})
    void ifMatchUsesStrongComparison(String header, boolean satisfied) {
        assertThat(EntityTags.ifMatchSatisfied(header, "\"7\"")).isEqualTo(satisfied);
    }

    @Test
    void parsesTagListsWithQuotedCommas() {
        assertThat(EntityTags.parse("\"a,b\", W/\"c\" ,\"d\"")).containsExactly("\"a,b\"", "W/\"c\"", "\"d\"");
    }
}
