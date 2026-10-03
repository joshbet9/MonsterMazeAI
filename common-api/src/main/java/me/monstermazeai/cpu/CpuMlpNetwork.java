package me.monstermazeai.cpu;

/**
 * Small dependency-free dense neural layer for production CPU inference.
 *
 * <p>Weights are immutable and the hidden buffer is reused, so forward passes
 * perform only primitive arithmetic and no heap allocation.
 */
public final class CpuMlpNetwork {
    private final int inputCount;
    private final int hiddenCount;
    private final int outputCount;
    private final float[] hiddenWeights;
    private final float[] hiddenBias;
    private final float[] outputWeights;
    private final float[] outputBias;
    private final float[] hidden;

    public CpuMlpNetwork(
            int inputCount,
            int hiddenCount,
            int outputCount,
            float[] hiddenWeights,
            float[] hiddenBias,
            float[] outputWeights,
            float[] outputBias) {
        if (inputCount <= 0 || hiddenCount <= 0 || outputCount <= 0) {
            throw new IllegalArgumentException("Network dimensions must be positive");
        }
        requireLength("hiddenWeights", hiddenWeights, hiddenCount * inputCount);
        requireLength("hiddenBias", hiddenBias, hiddenCount);
        requireLength("outputWeights", outputWeights, outputCount * hiddenCount);
        requireLength("outputBias", outputBias, outputCount);

        this.inputCount = inputCount;
        this.hiddenCount = hiddenCount;
        this.outputCount = outputCount;
        this.hiddenWeights = hiddenWeights.clone();
        this.hiddenBias = hiddenBias.clone();
        this.outputWeights = outputWeights.clone();
        this.outputBias = outputBias.clone();
        this.hidden = new float[hiddenCount];
    }

    public int inputCount() { return inputCount; }
    public int hiddenCount() { return hiddenCount; }
    public int outputCount() { return outputCount; }

    /**
     * Evaluate one layer stack. Inputs and outputs must be caller-owned reusable buffers.
     */
    public void forward(float[] input, float[] output) {
        if (input == null || input.length != inputCount) {
            throw new IllegalArgumentException("Expected " + inputCount + " inputs");
        }
        if (output == null || output.length != outputCount) {
            throw new IllegalArgumentException("Expected " + outputCount + " outputs");
        }

        for (int h = 0; h < hiddenCount; h++) {
            float sum = hiddenBias[h];
            int base = h * inputCount;
            for (int i = 0; i < inputCount; i++) {
                sum += hiddenWeights[base + i] * input[i];
            }
            hidden[h] = tanh(sum);
        }

        for (int o = 0; o < outputCount; o++) {
            float sum = outputBias[o];
            int base = o * hiddenCount;
            for (int h = 0; h < hiddenCount; h++) {
                sum += outputWeights[base + h] * hidden[h];
            }
            output[o] = sum;
        }
    }

    private static float tanh(float value) {
        return (float) Math.tanh(value);
    }

    private static void requireLength(String name, float[] values, int expected) {
        if (values == null || values.length != expected) {
            throw new IllegalArgumentException(name + " expected " + expected + " values");
        }
    }
}
