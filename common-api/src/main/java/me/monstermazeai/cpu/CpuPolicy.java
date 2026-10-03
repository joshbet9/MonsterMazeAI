package me.monstermazeai.cpu;

/**
 * Production brain contract shared by simulation and the Minecraft server.
 *
 * <p>Implementations must not reference Bukkit, NMS, Minecraft client classes,
 * file/network APIs, or training infrastructure.
 */
public interface CpuPolicy {

    /**
     * Select a tactical intent. The caller controls when this expensive/slower
     * layer is invoked (normally on a small cadence and on important events).
     */
    void decideTactical(float[] observationFeatures, CpuProfile profile, CpuIntent outIntent);

    /**
     * Select the per-tick locomotion/ability action.
     *
     * <p>This must be a direct policy evaluation. The implementation must not
     * enumerate candidate actions and simulate them.
     */
    void decideLocomotion(float[] observationFeatures,
                          CpuProfile profile,
                          CpuIntent intent,
                          CpuAction outAction);

    /**
     * Called when a CPU run starts or the policy state must be reset.
     */
    void reset(long seed);
}
