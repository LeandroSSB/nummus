package com.leandrossb.nummus.interfaces;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** HTTP surface limits: request bodies above maxBodyBytes are refused with
 *  413 before they are buffered. Merchant writes are small JSON; 1 MiB is
 *  generous headroom. */
@ConfigurationProperties(prefix = "nummus.http")
public record HttpProperties(@DefaultValue("1048576") int maxBodyBytes) {
}
