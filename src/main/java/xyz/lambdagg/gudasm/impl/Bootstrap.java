package xyz.lambdagg.gudasm.impl;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import xyz.lambdagg.gudasm.api.v1.AsmInitializer;
import xyz.lambdagg.gudasm.api.v1.ClassCache;
import xyz.lambdagg.gudasm.util.FileUtils;

import java.io.IOException;
import java.lang.invoke.MethodHandle;

// Bootstraps all the mess we make.
public class Bootstrap {
    public static boolean enableCache = false;

    public static void setup() {
        // Load the configuration
        try {
            Configuration.load();
        } catch (IOException e) {
            e.printStackTrace();
        }

        FabricLoader loader = FabricLoader.getInstance();
        RegistryImpl registry = RegistryImpl.INSTANCE;

        registry.setFrozen(false);
        for (EntrypointContainer<AsmInitializer> container : loader.getEntrypointContainers("gud_asm", AsmInitializer.class)) {
            AsmInitializer initializer = container.getEntrypoint();
            if (initializer != null) {
                initializer.onInitializeAsm();
            }
        }
        registry.setFrozen(true);

        // Let the cache load itself
        ClassCache cache = registry.getCache().orElse(null);
        if (cache != null) {
            try {
                cache.load();
                enableCache = true;
            } catch (IOException e) {
                new RuntimeException("Failed to load class cache " + cache.getName(), e).printStackTrace();
            }
        }

        // Clean out the class dump if dumping is enabled
        if (Configuration.DUMP.get() != Configuration.DumpMode.OFF) {
            try {
                FileUtils.delete(loader.getGameDir().resolve("gudASMDump"));
            } catch (IOException ignored) {
            }
        }

        // Hack into knot.
        ClassLoader classLoader = Bootstrap.class.getClassLoader();
        ASMMixinTransformer.setClassLoader(classLoader); // Needed for ASM, don't ask

        try {
            // Get classes we can't normally access
            Class<? extends ClassLoader> KnotClassLoader = ReflectionHelper.loadClass(classLoader, "net.fabricmc.loader.impl.launch.knot.KnotClassLoader");
            Class<?> KnotClassDelegate = ReflectionHelper.loadClass(classLoader, "net.fabricmc.loader.impl.launch.knot.KnotClassDelegate");

            // Get the class delegate
            MethodHandle KnotClassLoader$delegate$getter = ReflectionHelper.findGetter(KnotClassLoader, classLoader, "delegate", KnotClassDelegate);
            Object KnotClassLoader$delegate = KnotClassLoader$delegate$getter.invoke();

            // Get the transformer proxy
            MethodHandle KnotClassDelegate$mixinTransformer$getter = ReflectionHelper.findGetter(KnotClassDelegate, KnotClassLoader$delegate, "mixinTransformer", IMixinTransformer.class);
            IMixinTransformer KnotClassDelegate$mixinTransformer = (IMixinTransformer) KnotClassDelegate$mixinTransformer$getter.invokeExact();

            // Get the environment's transformer
            MethodHandle MixinEnvironment$transformer$getter = ReflectionHelper.findStaticGetter(MixinEnvironment.class, "transformer", IMixinTransformer.class);
            IMixinTransformer originalTransformer = (IMixinTransformer) MixinEnvironment$transformer$getter.invokeExact();

            // Clear it, otherwise it will kill Minecraft
            MethodHandle MixinEnvironment$transformer$setter = ReflectionHelper.findStaticSetter(MixinEnvironment.class, "transformer", IMixinTransformer.class);
            MixinEnvironment$transformer$setter.invokeExact((IMixinTransformer) null);

            // Create our transformer
            ASMMixinTransformer customTransformer = enableCache ? new ASMMixinTransformer.Cache(originalTransformer, cache) : new ASMMixinTransformer(originalTransformer);
            RegistryImpl.INSTANCE.setTransformer(customTransformer);

            // Restore the original to keep the environment as sane as possible
            MixinEnvironment$transformer$setter.invokeExact(originalTransformer);

            // Set our custom transformer so it will be used in future class loads
            MethodHandle KnotClassDelegate$mixinTransformer$setter = ReflectionHelper.findSetter(KnotClassDelegate, KnotClassLoader$delegate, "mixinTransformer", IMixinTransformer.class);
            KnotClassDelegate$mixinTransformer$setter.invokeExact((IMixinTransformer) customTransformer);
        } catch (Throwable t) {
            new RuntimeException("Failed to hook into Knot", t).printStackTrace();
            System.exit(0);
            // Unreachable
            throw new RuntimeException("Failed to hook into Knot", t);
        }
    }
}
