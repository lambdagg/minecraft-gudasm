package xyz.lambdagg.gudasm.impl;

import xyz.lambdagg.gudasm.api.v1.AsmUtils;
import xyz.lambdagg.gudasm.api.v1.Identifier;
import xyz.lambdagg.gudasm.api.v1.Transformer;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import xyz.lambdagg.gudasm.api.v1.annotation.ForceInline;

import java.util.List;

/**
 * A simple transformer to enable some JVM abuse.
 */
public class BootstrapTransformer implements Transformer {
    // On the off chance that ForceInline is not around, we should not use it.
    private static final boolean ENABLED;
    private static final Type FORCEBOOTLOADER = Type.getObjectType("xyz/lambdagg/gudasm/api/v0/annotation/ForceBootloader");
    private static final Type ASM_FORCEINLINE = Type.getObjectType("xyz/lambdagg/gudasm/api/v0/annotation/ForceInline");
    private static final Type JVM_FORCEINLINE = Type.getObjectType("jdk/internal/vm/annotation/ForceInline");

    static {
        boolean enable;
        try {
            ReflectionHelper.loadClass("jdk.internal.vm.annotation.ForceInline");
            enable = true;
        } catch (Throwable ignored) {
            enable = false;
        }
        ENABLED = enable;
    }

    @Override
    public Identifier getName() {
        return new Identifier("gud_asm", "bootstrap");
    }

    // Special case, this is always true when called.
    @Override
    public boolean handlesClass(String name, String transformedName) {
        return ENABLED;
    }

    @Override
    public boolean transform(ClassNode classNode, Flags flags) {
        boolean changed = AsmUtils.removeAnnotations(classNode, FORCEBOOTLOADER);

        for (MethodNode method : classNode.methods) {
            List<AnnotationNode> annotations = AsmUtils.getAnnotations(method, ASM_FORCEINLINE);
            if (!annotations.isEmpty()) {
                for (AnnotationNode annotation : annotations) {
                    annotation.desc = JVM_FORCEINLINE.getDescriptor();
                    changed = true;
                }
            }
        }

        return changed;
    }
}
