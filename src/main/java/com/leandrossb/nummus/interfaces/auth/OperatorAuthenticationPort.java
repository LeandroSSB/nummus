package com.leandrossb.nummus.interfaces.auth;

import java.util.Optional;

/** Resolves a raw bearer credential to an operator. Implemented by the merchants module. */
public interface OperatorAuthenticationPort {

  Optional<AuthenticatedOperator> authenticate(String rawBearerCredential);
}
