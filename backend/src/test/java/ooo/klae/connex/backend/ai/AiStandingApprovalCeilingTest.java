package ooo.klae.connex.backend.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import ooo.klae.connex.backend.dto.AiStandingApprovalPolicyRequest;
import ooo.klae.connex.backend.exceptions.BadRequestException;

class AiStandingApprovalCeilingTest {
    private AiStandingApprovalPolicyRequest request(List<String> tools, int daily, int run, String version) {
        return new AiStandingApprovalPolicyRequest(false, tools, daily, run, version);
    }

    @Test
    void onlyExactReviewedToolsRoundTripAllSubsets() {
        for (int mask = 0; mask < 16; mask++) {
            assertEquals(mask, AiStandingApprovalCeiling.validate(request(
                    AiStandingApprovalCeiling.tools(mask), 100, 10, "9223372036854775807")));
        }
        assertThrows(IllegalStateException.class, () -> AiStandingApprovalCeiling.tools(16));
        assertThrows(IllegalStateException.class, () -> AiStandingApprovalCeiling.tools(-1));
    }

    static Stream<String> prohibitedTools() {
        return Stream.of("assign_owner", "send_email", "delete_record", "archive_record", "merge_records",
                "change_permissions", "grant_consent", "enable_workflow", "create_record", "create_report",
                "create_document", "complete_task", "UPDATE_RECORD_FIELDS", "unknown", "");
    }

    @ParameterizedTest
    @MethodSource("prohibitedTools")
    void configurationCannotAdmitForbiddenUnknownOrFutureTools(String tool) {
        assertThrows(BadRequestException.class, () -> AiStandingApprovalCeiling.validate(
                request(List.of(tool), 100, 10, "1")));
    }

    @Test
    void malformedChoicesRefuseRatherThanClamp() {
        assertThrows(BadRequestException.class, () -> AiStandingApprovalCeiling.validate(null));
        assertThrows(BadRequestException.class, () -> AiStandingApprovalCeiling.validate(request(null, 100, 10, "1")));
        assertThrows(BadRequestException.class, () -> AiStandingApprovalCeiling.validate(request(
                java.util.Arrays.asList((String) null), 100, 10, "1")));
        assertThrows(BadRequestException.class, () -> AiStandingApprovalCeiling.validate(request(
                List.of("remove_tag", "remove_tag"), 100, 10, "1")));
        for (int[] caps : List.of(new int[]{0, 0}, new int[]{10001, 1}, new int[]{10, 11}, new int[]{10, -1})) {
            assertThrows(BadRequestException.class, () -> AiStandingApprovalCeiling.validate(
                    request(List.of(), caps[0], caps[1], "1")));
        }
        for (String version : List.of("0", "01", "+1", " 1", "1.0", "9223372036854775808")) {
            assertThrows(BadRequestException.class, () -> AiStandingApprovalCeiling.version(version));
        }
    }

    @Test
    void epochNeverWrapsOrReusesAnEarlierValue() {
        assertEquals(2, AiStandingApprovalCeiling.nextEpoch(1));
        assertEquals(Long.MAX_VALUE, AiStandingApprovalCeiling.nextEpoch(Long.MAX_VALUE - 1));
        for (long epoch : new long[]{Long.MIN_VALUE, -1, 0, Long.MAX_VALUE}) {
            assertThrows(IllegalStateException.class, () -> AiStandingApprovalCeiling.nextEpoch(epoch));
        }
    }
}
