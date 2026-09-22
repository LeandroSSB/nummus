package com.leandrossb.nummus.interfaces;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** HTTP surface limits: request bodies above maxBodyBytes are refused with
 *  413 before they are buffered. Merchant writes are small JSON; 1 MiB is
 *  generous headroom. */
@ConfigurationProperties(prefix = "nummus.http")
@Validated
public record HttpProperties(@DefaultValue("1048576") @Min(1) @Max(2147483646) int maxBodyBytes) {
}
