package cn.xu.medical.agent.permission;

/**
 * A single gate in the permission pipeline.
 * Returns null if this gate has no opinion (delegates to next gate),
 * or a PermissionDecision if it rules definitively.
 */
@FunctionalInterface
public interface PermissionGate {

    /**
     * Evaluate this gate.
     *
     * @param ctx    the permission context
     * @return null to pass to the next gate, or a decision to stop the chain
     */
    PermissionDecision evaluate(PermissionContext ctx);

    default PermissionLevel level() {
        return null;
    }
}
