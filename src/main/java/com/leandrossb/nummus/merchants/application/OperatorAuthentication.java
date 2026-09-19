package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedOperator;
import com.leandrossb.nummus.interfaces.auth.OperatorAuthenticationPort;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class OperatorAuthentication implements OperatorAuthenticationPort {

  private final OperatorKeysService operatorKeys;

  public OperatorAuthentication(OperatorKeysService operatorKeys) {
    this.operatorKeys = operatorKeys;
  }

  @Override
  public Optional<AuthenticatedOperator> authenticate(String rawBearerCredential) {
    return operatorKeys.findByRawKey(rawBearerCredential)
        .map(key -> new AuthenticatedOperator(key.publicId()));
  }
}
