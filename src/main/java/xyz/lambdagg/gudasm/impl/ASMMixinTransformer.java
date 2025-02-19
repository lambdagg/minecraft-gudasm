package xyz.lambdagg.gudasm.impl;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.IExtensionRegistry;
import xyz.lambdagg.gudasm.api.v1.AsmUtils;
import xyz.lambdagg.gudasm.api.v1.ClassCache;
import xyz.lambdagg.gudasm.api.v1.Transformer;
import xyz.lambdagg.gudasm.util.Locker;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Our custom "mixin" transformer.
 */
public class ASMMixinTransformer implements IMixinTransformer {
    public static final Set<String> BLACKLIST = new HashSet<>(Arrays.asList(
            "net.gudenau.minecraft.asm.",
            "org.objectweb.asm.",
            "com.google.gson.",
            "org.lwjgl.",
            "it.unimi.dsi.fastutil."
    ));

    private static final Type ANNOTATION_FORCE_BOOTLOADER = Type.getObjectType("xyz/lambdagg/gudasm/api/v1/annotation/ForceBootloader");

    private static final Transformer BOOTSTRAP_TRANSFORMER = new BootstrapTransformer();

    private static final MethodHandle ClassLoader$defineClass;
    private static final ExecutorService DUMP_SERVICE = Executors.newFixedThreadPool(1);
    private static ClassLoader classLoader;

    static {
        try {
            ClassLoader$defineClass = ReflectionHelper.findStatic(
                    ClassLoader.class,
                    "defineClass1",
                    Class.class,
                    ClassLoader.class, String.class, byte[].class, int.class, int.class, ProtectionDomain.class, String.class
            );
        } catch (ReflectiveOperationException e) {
            new RuntimeException("Failed to get ClassLoader.defineClass1", e).printStackTrace();
            System.exit(0);
            // Unreachable, makes javac happy
            throw new RuntimeException("Failed to get ClassLoader.defineClass1", e);
        }
    }

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            DUMP_SERVICE.shutdown();
            try {
                DUMP_SERVICE.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
            } catch (InterruptedException ignored) {
            }
        }, "gudASM Dumper Service Cleanup"));
    }

    private final Set<String> seenClasses = new HashSet<>();
    private final Locker seenClassesLocker = new Locker();
    private final IMixinTransformer parent;
    private final List<Transformer> transformers;
    private final List<Transformer> earlyTransformers;
    private final boolean forceDump = Configuration.DUMP.get() == Configuration.DumpMode.FORCE;
    private final boolean dump = Configuration.DUMP.get() == Configuration.DumpMode.ON || forceDump;

    ASMMixinTransformer(IMixinTransformer parent) {
        this.parent = parent;
        transformers = RegistryImpl.INSTANCE.getTransformers();
        earlyTransformers = RegistryImpl.INSTANCE.getEarlyTransformers();
    }

    public static void setClassLoader(ClassLoader classLoader) {
        ASMMixinTransformer.classLoader = classLoader;
    }

    @Override
    public void audit(MixinEnvironment environment) {
        this.parent.audit(environment);
    }

    @Override
    public List<String> reload(String mixinClass, ClassNode classNode) {
        return this.parent.reload(mixinClass, classNode);
    }

    @Override
    public boolean computeFramesForClass(MixinEnvironment environment, String name, ClassNode classNode) {
        return this.parent.computeFramesForClass(environment, name, classNode);
    }

    @Override
    public byte[] transformClassBytes(String name, String transformedName, byte[] basicClass) {
        if (seenClassesLocker.readLock(() -> seenClasses.contains(name))) {
            return basicClass;
        }
        if (seenClassesLocker.writeLock(() -> {
            if (seenClasses.contains(name)) {
                return true;
            } else {
                seenClasses.add(name);
                return false;
            }
        })) {
            return parent.transformClassBytes(name, transformedName, basicClass);
        }

        for (String prefix : BLACKLIST) {
            if (name.startsWith(prefix)) {
                byte[] transformedClass = parent.transformClassBytes(name, transformedName, basicClass);
                if (forceDump) {
                    dump(name, basicClass);
                }
                return bootstrap(cache(basicClass, () -> transformedClass));
            }
        }
        return cache(basicClass, () -> {
            if (basicClass == null) {
                return parent.transformClassBytes(name, transformedName, basicClass);
            }

            boolean shouldBootstrap = shouldBootstrap(basicClass);
            AtomicBoolean modified = new AtomicBoolean(forceDump);

            byte[] bytecode = basicClass;
            if (!earlyTransformers.isEmpty()) {
                bytecode = transform(name, transformedName, bytecode, earlyTransformers, modified);
            }

            bytecode = parent.transformClassBytes(name, transformedName, bytecode);

            //FIXME, this is stupid
            List<Transformer> transformers = this.transformers;
            if (shouldBootstrap) {
                List<Transformer> newList = new ArrayList<>(this.transformers);
                newList.add(BOOTSTRAP_TRANSFORMER);
                transformers = newList;
            }
            if (!transformers.isEmpty()) {
                bytecode = transform(name, transformedName, bytecode, transformers, modified);
            }

            if (dump && modified.get()) {
                dump(name, bytecode);
            }

            return bootstrap(bytecode, shouldBootstrap);
        });
    }

    @Override
    public byte[] transformClass(MixinEnvironment environment, String name, byte[] classBytes) {
        return this.parent.transformClass(environment, name, classBytes);
    }

    @Override
    public boolean transformClass(MixinEnvironment environment, String name, ClassNode classNode) {
        return this.parent.transformClass(environment, name, classNode);
    }

    @Override
    public byte[] generateClass(MixinEnvironment environment, String name) {
        return this.parent.generateClass(environment, name);
    }

    @Override
    public boolean generateClass(MixinEnvironment environment, String name, ClassNode classNode) {
        return this.parent.generateClass(environment, name, classNode);
    }

    @Override
    public IExtensionRegistry getExtensions() {
        return this.parent.getExtensions();
    }

    private void dump(String name, byte[] bytecode) {
        DUMP_SERVICE.submit(() -> {
            Path path = FabricLoader.getInstance().getGameDir().resolve("gudASMDump");
            String[] split = name.split("\\.");
            for (int i = 0; i < split.length - 1; i++) {
                String s = split[i];
                path = path.resolve(s);
            }
            if (!Files.exists(path)) {
                try {
                    Files.createDirectories(path);
                } catch (IOException ignored) {
                }
            }
            path = path.resolve(split[split.length - 1] + ".class");
            try (OutputStream stream = Files.newOutputStream(path, StandardOpenOption.CREATE)) {
                stream.write(bytecode);
            } catch (IOException ignored) {
            }
        });
    }

    private byte[] transform(String name, String transformedName, byte[] bytecode, List<Transformer> transformers, AtomicBoolean parentModifier) {
        List<Transformer> validTransformers = new ArrayList<>();
        for (Transformer transformer : transformers) {
            if (transformer.handlesClass(name, transformedName)) {
                validTransformers.add(transformer);
            }
        }

        if (validTransformers.isEmpty()) {
            return bytecode;
        }

        ClassNode classNode = new ClassNode();
        new ClassReader(bytecode).accept(classNode, 0);
        boolean modified = false;
        TransformerFlagsImpl flags = new TransformerFlagsImpl();
        for (Transformer transformer : validTransformers) {
            modified |= transformer.transform(classNode, flags);
        }
        if (!modified) {
            return bytecode;
        }

        ClassWriter writer = new ClassWriter(flags.getClassWriterFlags()) {
            // Fixes an issue with stack calculations
            @Override
            protected ClassLoader getClassLoader() {
                return classLoader;
            }
        };
        classNode.accept(writer);
        parentModifier.set(true);
        return writer.toByteArray();
    }

    byte[] cache(byte[] original, Supplier<byte[]> transformed) {
        return transformed.get();
    }

    private boolean shouldBootstrap(byte[] bytecode) {
        if (bytecode == null) {
            return false;
        }

        ClassNode classNode = new ClassNode();
        new ClassReader(bytecode).accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return AsmUtils.hasAnnotation(classNode, ANNOTATION_FORCE_BOOTLOADER);
    }

    private byte[] bootstrap(byte[] bytecode) {
        return bootstrap(bytecode, shouldBootstrap(bytecode));
    }

    private byte[] bootstrap(byte[] bytecode, boolean shouldBootstrap) {
        if (bytecode == null) {
            return null;
        }

        if (shouldBootstrap) {
            try {
                ClassLoader$defineClass.invoke(
                        (ClassLoader) null, // AKA bootstrap ClassLoader
                        (String) null, // Let the JVM figure it out
                        bytecode,
                        0,
                        bytecode.length,
                        (ProtectionDomain) null,
                        (String) null
                );
            } catch (Throwable throwable) {
                new RuntimeException("Failed to force a class into the bootstrap ClassLoader", throwable).printStackTrace();
                System.exit(0);
            }

            return null;
        } else {
            return bytecode;
        }
    }

    public void blacklistPackage(String name) {
        BLACKLIST.add(name);
    }

    static class Cache extends ASMMixinTransformer {
        private final ClassCache cache;

        Cache(IMixinTransformer parent, ClassCache cache) {
            super(parent);
            this.cache = cache;
        }

        @Override
        byte[] cache(byte[] original, Supplier<byte[]> transformer) {
            if (original == null) {
                return transformer.get();
            }

            Optional<byte[]> result = cache.getEntry(original);
            if (result.isPresent()) {
                return result.get();
            } else {
                byte[] transformed = transformer.get();
                if (transformed != null) {
                    cache.putEntry(original, transformed);
                }
                return transformed;
            }
        }
    }
}
