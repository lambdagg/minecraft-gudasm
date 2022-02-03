package xyz.lambdagg.gudasm.impl;

import org.objectweb.asm.ClassWriter;
import xyz.lambdagg.gudasm.api.v1.Transformer;

public class TransformerFlagsImpl implements Transformer.Flags {
    private boolean computeMaxes = false;
    private boolean computeFrames = false;

    public void requestMaxes() {
        computeMaxes = true;
    }

    public void requestFrames() {
        computeFrames = true;
    }

    public int getClassWriterFlags() {
        return (computeFrames ? ClassWriter.COMPUTE_FRAMES : 0) |
                (computeMaxes ? ClassWriter.COMPUTE_MAXS : 0);
    }
}
