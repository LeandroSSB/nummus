package com.leandrossb.nummus.interfaces.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a merchant-facing write as idempotent: the Idempotency-Key reserves a
 * slot in the same transaction as the handler's writes, and the serialized
 * response is attached before commit — retries replay instead of re-executing.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
}
