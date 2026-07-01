package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One frame of the time-travel replay: the contacts, companies, and deals that existed as of
 * {@link #asOf}, each carrying its as-of warmth band, employer, or outcome. A node absent from
 * these lists did not exist yet (or no longer exists) at this instant.
 *
 * <p>Membership is derived from each row's {@code created_at} (insert time), so for imported/backdated
 * data a record can appear a little later than its real-world relationship start, and hard-deleted
 * records never appear in past frames. Treat a replay as a faithful reconstruction of the app's own
 * history, not an authoritative record of when relationships truly began.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReplayFrameDto {
    /** The frame's calendar date as a UTC {@code yyyy-MM-dd} string. */
    private String asOf;
    /** Contacts present as of this frame. */
    private List<ReplayContactDto> contacts;
    /** Companies present as of this frame. */
    private List<ReplayCompanyDto> companies;
    /** Deals present as of this frame. */
    private List<ReplayDealDto> deals;
}
