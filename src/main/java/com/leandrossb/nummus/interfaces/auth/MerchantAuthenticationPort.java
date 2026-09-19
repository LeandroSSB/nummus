package com.leandrossb.nummus.interfaces.auth;

import java.util.Optional;

/** Resolves a raw bearer credential to a merchant. Implemented by the merchants module. */
public interface MerchantAuthenticationPort {

  Optional<AuthenticatedMerchant> authenticate(String rawBearerCredential);
}
