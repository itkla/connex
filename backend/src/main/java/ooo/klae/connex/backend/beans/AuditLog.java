package ooo.klae.connex.backend.beans;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonRawValue;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AuditLog {
    private int id;
    private Integer workspaceId;
    private Integer orgId;
    private String action;
    private String entityType;
    private Integer entityId;
    private Integer actorId;
    private Integer integrityWorkspaceId;
    private Integer integrityOrgId;
    private Integer integrityActorId;
    private String integrityReferenceState;
    private String actorLabel;
    private String targetLabel;
    private String outcome;
    private String summary;
    private String changes;
    private String context;
    private String ipAddress;
    private String userAgent;
    private String sessionId;
    private String requestId;
    private String untrustedClientAssertedCorrelationHmac;
    private String chainScopeType;
    private Integer chainScopeId;
    private Long chainIndex;
    private String prevHash;
    private String rowHash;
    private String createdAt;
    private String currentActorLabel;
    private boolean contentRedacted;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public Integer getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(Integer workspaceId) {
        this.workspaceId = workspaceId;
    }

    public Integer getOrgId() {
        return orgId;
    }

    public void setOrgId(Integer orgId) {
        this.orgId = orgId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public Integer getEntityId() {
        return entityId;
    }

    public void setEntityId(Integer entityId) {
        this.entityId = entityId;
    }

    public Integer getActorId() {
        return actorId;
    }

    public void setActorId(Integer actorId) {
        this.actorId = actorId;
    }

    @JsonIgnore
    public Integer getIntegrityWorkspaceId() {
        return integrityWorkspaceId;
    }

    public void setIntegrityWorkspaceId(Integer integrityWorkspaceId) {
        this.integrityWorkspaceId = integrityWorkspaceId;
    }

    @JsonIgnore
    public Integer getIntegrityOrgId() {
        return integrityOrgId;
    }

    public void setIntegrityOrgId(Integer integrityOrgId) {
        this.integrityOrgId = integrityOrgId;
    }

    @JsonIgnore
    public Integer getIntegrityActorId() {
        return integrityActorId;
    }

    public void setIntegrityActorId(Integer integrityActorId) {
        this.integrityActorId = integrityActorId;
    }

    @JsonIgnore
    public String getIntegrityReferenceState() {
        return integrityReferenceState;
    }

    public void setIntegrityReferenceState(String integrityReferenceState) {
        this.integrityReferenceState = integrityReferenceState;
    }

    public String getActorLabel() {
        return actorLabel;
    }

    public void setActorLabel(String actorLabel) {
        this.actorLabel = actorLabel;
    }

    public String getTargetLabel() {
        return targetLabel;
    }

    public void setTargetLabel(String targetLabel) {
        this.targetLabel = targetLabel;
    }

    public String getOutcome() {
        return outcome;
    }

    public void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    @JsonRawValue
    public String getChanges() {
        return changes;
    }

    public void setChanges(String changes) {
        this.changes = changes;
    }

    @JsonRawValue
    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = context;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getUntrustedClientAssertedCorrelationHmac() {
        return untrustedClientAssertedCorrelationHmac;
    }

    public void setUntrustedClientAssertedCorrelationHmac(
            String untrustedClientAssertedCorrelationHmac) {
        this.untrustedClientAssertedCorrelationHmac =
            untrustedClientAssertedCorrelationHmac;
    }

    public String getChainScopeType() {
        return chainScopeType;
    }

    public void setChainScopeType(String chainScopeType) {
        this.chainScopeType = chainScopeType;
    }

    public Integer getChainScopeId() {
        return chainScopeId;
    }

    public void setChainScopeId(Integer chainScopeId) {
        this.chainScopeId = chainScopeId;
    }

    public Long getChainIndex() {
        return chainIndex;
    }

    public void setChainIndex(Long chainIndex) {
        this.chainIndex = chainIndex;
    }

    public String getPrevHash() {
        return prevHash;
    }

    public void setPrevHash(String prevHash) {
        this.prevHash = prevHash;
    }

    public String getRowHash() {
        return rowHash;
    }

    public void setRowHash(String rowHash) {
        this.rowHash = rowHash;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getCurrentActorLabel() {
        return currentActorLabel;
    }

    public void setCurrentActorLabel(String currentActorLabel) {
        this.currentActorLabel = currentActorLabel;
    }

    public boolean isContentRedacted() {
        return contentRedacted;
    }

    public void setContentRedacted(boolean contentRedacted) {
        this.contentRedacted = contentRedacted;
    }
}
