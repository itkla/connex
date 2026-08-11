package ooo.klae.connex.backend.controllers;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import ooo.klae.connex.backend.dto.RecordCommentCountDto;
import ooo.klae.connex.backend.dto.RecordCommentCreateRequest;
import ooo.klae.connex.backend.dto.RecordCommentCreateThreadRequest;
import ooo.klae.connex.backend.dto.RecordCommentDto;
import ooo.klae.connex.backend.dto.RecordCommentThreadDto;
import ooo.klae.connex.backend.services.RecordCommentService;
import ooo.klae.connex.backend.tenant.Permission;
import ooo.klae.connex.backend.tenant.RequirePermission;
import ooo.klae.connex.backend.tenant.TenantJournalAttributable;

/** REST endpoints for workspace-local record comment threads and redaction. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
@TenantJournalAttributable
public class RecordCommentController {
    private final RecordCommentService recordCommentService;

    @GetMapping("/comment-threads")
    public List<RecordCommentThreadDto> listThreads(
            @Pattern(regexp = "^(person|company|deal)$") @RequestParam String targetType,
            @Positive @RequestParam int targetId,
            @Pattern(regexp = "^(open|resolved|all)$")
            @RequestParam(defaultValue = "open") String state,
            @Min(1) @Max(100) @RequestParam(defaultValue = "20") int limit,
            @Min(0) @RequestParam(defaultValue = "0") int offset) {
        return recordCommentService
            .listThreads(targetType, targetId, state, limit, offset)
            .stream()
            .map(RecordCommentThreadDto::from)
            .toList();
    }

    @GetMapping("/comment-threads/count")
    public RecordCommentCountDto countThreads(
            @Pattern(regexp = "^(person|company|deal)$") @RequestParam String targetType,
            @Positive @RequestParam int targetId,
            @Pattern(regexp = "^(open|resolved|all)$")
            @RequestParam(defaultValue = "open") String state) {
        return new RecordCommentCountDto(
            recordCommentService.countThreads(targetType, targetId, state));
    }

    @GetMapping("/comment-threads/{threadId}")
    public RecordCommentThreadDto getThread(@Positive @PathVariable long threadId) {
        return RecordCommentThreadDto.from(recordCommentService.getThread(threadId));
    }

    @PostMapping("/comment-threads")
    @RequirePermission(Permission.COMMENT_CREATE)
    public RecordCommentThreadDto createThread(
            @Valid @RequestBody RecordCommentCreateThreadRequest request) {
        return RecordCommentThreadDto.from(recordCommentService.createThread(
            request.getTargetType(),
            request.getTargetId(),
            request.getContent(),
            request.getClientToken()));
    }

    @PostMapping("/comment-threads/{threadId}/comments")
    @RequirePermission(Permission.COMMENT_CREATE)
    public RecordCommentDto reply(
            @Positive @PathVariable long threadId,
            @Valid @RequestBody RecordCommentCreateRequest request) {
        return RecordCommentDto.from(recordCommentService.reply(
            threadId, request.getContent(), request.getClientToken()));
    }

    @DeleteMapping("/comments/{commentId}")
    public void deleteComment(@Positive @PathVariable long commentId) {
        recordCommentService.deleteComment(commentId);
    }
}
