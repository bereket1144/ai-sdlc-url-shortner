package io.github.bereket1144.agentcontroller.agent;

import io.github.bereket1144.agentcontroller.agent.RoleCatalog.InputKind;

/** A role asked for an input kind that its definition does not list. The failure code is ACCESS_DENIED. */
public final class InputAccessDeniedException extends RuntimeException {

    private final String roleId;
    private final InputKind kind;

    public InputAccessDeniedException(String roleId, InputKind kind) {
        super("ACCESS_DENIED: Role " + roleId + " cannot read input " + kind);
        this.roleId = roleId;
        this.kind = kind;
    }

    public String code() {
        return "ACCESS_DENIED";
    }

    public String roleId() {
        return roleId;
    }

    public InputKind kind() {
        return kind;
    }
}
