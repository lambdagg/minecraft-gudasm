package xyz.lambdagg.gudasm.api.v1;

import xyz.lambdagg.gudasm.impl.RegistryImpl;

/**
 * The place to register your gross ASM hacks.
 */
public interface AsmRegistry {
    /**
     * Gets the instance of the registry.
     *
     * @return The registry
     */
    static AsmRegistry getInstance() {
        return RegistryImpl.INSTANCE;
    }

    /**
     * Registers a class transformer for transforming classes before mixins.
     * <p>
     * This one should not be used unless it is 100% required.
     *
     * @param transformer The transformer to register
     */
    void registerEarlyTransformer(Transformer transformer);

    /**
     * Registers a class transformer for transforming classes after mixins.
     * <p>
     * This is the most compatible one.
     *
     * @param transformer The transformer to register
     */
    void registerTransformer(Transformer transformer);

    /**
     * Registers a class cache.
     *
     * @param cache The class cache
     */
    void registerClassCache(ClassCache cache);
}
