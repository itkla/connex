package ooo.klae.connex.backend.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import lombok.Data;
import lombok.NoArgsConstructor;

import ooo.klae.connex.backend.beans.ApprovalStepApprover;

/**
 * One approver on an approval step: either a named workspace member ({@code user} with
 * {@code userId}) or any member holding {@code DOCUMENT_APPROVE} ({@code any_approver}).
 */
@Data
@NoArgsConstructor
public class ApprovalStepApproverDto {

    @NotBlank
    @Pattern(regexp = "user|any_approver", message = "approverKind must be user or any_approver")
    private String approverKind;

    private Integer userId;

    public static ApprovalStepApproverDto from(ApprovalStepApprover approver) {
        if (approver == null) return null;
        ApprovalStepApproverDto dto = new ApprovalStepApproverDto();
        dto.approverKind = approver.getApproverKind();
        dto.userId = approver.getUserId();
        return dto;
    }

    public ApprovalStepApprover toBean() {
        ApprovalStepApprover approver = new ApprovalStepApprover();
        approver.setApproverKind(approverKind);
        approver.setUserId(userId);
        return approver;
    }
}
