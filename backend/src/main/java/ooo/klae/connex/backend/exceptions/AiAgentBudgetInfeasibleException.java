package ooo.klae.connex.backend.exceptions;

/** Refuses a new mission whose goal cannot execute within its immutable requested allowance. */
public final class AiAgentBudgetInfeasibleException extends BadRequestException {
    public AiAgentBudgetInfeasibleException() {
        super("mission_budget_infeasible");
    }

    @Override
    public String getCode() {
        return "mission_budget_infeasible";
    }
}
