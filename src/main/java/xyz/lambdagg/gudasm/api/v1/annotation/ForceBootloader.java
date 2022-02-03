package xyz.lambdagg.gudasm.api.v1.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Forces a class to be loaded by the bootloader {@link ClassLoader ClassLoader}, use sparingly.
 * <p>
 * gudenau disclaims any responsibility for damages caused by the use of {@link ForceBootloader ForceBootloader}.
 * <p>
 * All Rights Reserved.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ForceBootloader {
}
