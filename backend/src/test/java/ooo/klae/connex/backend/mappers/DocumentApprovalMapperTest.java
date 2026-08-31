package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import ooo.klae.connex.backend.beans.ApprovalStepApprover;
import ooo.klae.connex.backend.beans.Company;
import ooo.klae.connex.backend.beans.Deal;
import ooo.klae.connex.backend.beans.DealDocument;
import ooo.klae.connex.backend.beans.DocumentApproval;
import ooo.klae.connex.backend.beans.DocumentApprovalStep;
import ooo.klae.connex.backend.beans.Pipeline;
import ooo.klae.connex.backend.beans.Stage;
import ooo.klae.connex.backend.beans.User;
import ooo.klae.connex.backend.dto.ApprovalInboxRow;

class DocumentApprovalMapperTest extends AbstractMapperTest {
    @Autowired private DocumentApprovalMapper approvalMapper;
    @Autowired private DealDocumentMapper documentMapper;

    @Test
    void actionableStepCandidatesAreBoundedActorAndWorkspaceScoped() {
        User approver = newUser();
        User bystander = newUser();
        Company company = newCompany();
        Pipeline pipeline = newPipeline();
        Stage stage = newStage(pipeline, 0);
        Deal deal = newDeal(pipeline, stage, company);
        DealDocument document = new DealDocument();
        document.setWorkspaceId(workspace.getId());
        document.setDealId(deal.getId());
        document.setType("quote");
        document.setLocale("en");
        document.setStatus("pending_approval");
        document.setVersion(1);
        document.setTitle("Quote");
        document.setContent("{}");
        document.setCurrency("JPY");
        documentMapper.insert(document);
        DocumentApproval approval = new DocumentApproval();
        approval.setWorkspaceId(workspace.getId());
        approval.setDealId(deal.getId());
        approval.setDocumentId(document.getId());
        approval.setStatus("pending");
        approval.setMode("parallel");
        approval.setSeparationOfDuties("strict");
        approval.setPolicyBinding("none");
        approvalMapper.insert(approval);
        DocumentApprovalStep step = new DocumentApprovalStep();
        step.setWorkspaceId(workspace.getId());
        step.setApprovalId(approval.getId());
        step.setStepOrder(1);
        step.setName("Legal");
        step.setRequiredCount(1);
        step.setStatus("active");
        step.setOnExpiry("expire");
        approvalMapper.insertStep(step);
        ApprovalStepApprover assignment = new ApprovalStepApprover();
        assignment.setWorkspaceId(workspace.getId());
        assignment.setStepId(step.getId());
        assignment.setApproverKind("user");
        assignment.setUserId(approver.getId());
        approvalMapper.insertStepApprover(assignment);

        List<ApprovalInboxRow> rows = approvalMapper.findActionableSteps(
            workspace.getId(), approver.getId(), 0, 1);

        assertEquals(List.of(approval.getId()),
            rows.stream().map(ApprovalInboxRow::approvalId).toList());
        assertTrue(approvalMapper.findActionableSteps(
            workspace.getId(), bystander.getId(), 0, 1).isEmpty());
        assertTrue(approvalMapper.findActionableSteps(
            workspace.getId() + 1, approver.getId(), 0, 1).isEmpty());
    }
}
