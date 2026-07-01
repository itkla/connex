package ooo.klae.connex.backend.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The time-travel replay (#48) payload: an ordered series of frames reconstructing the relationship
 * graph as of each instant. Computed on read, never persisted.
 *
 * @see ooo.klae.connex.backend.services.MapReplayService
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MapReplayDto {
    /** Frames in chronological order; the last frame is the requested {@code to} date. */
    private List<ReplayFrameDto> frames;
}
