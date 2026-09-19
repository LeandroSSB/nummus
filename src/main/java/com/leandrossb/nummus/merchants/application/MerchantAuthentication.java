package com.leandrossb.nummus.merchants.application;

import com.leandrossb.nummus.interfaces.auth.AuthenticatedMerchant;
import com.leandrossb.nummus.interfaces.auth.MerchantAuthenticationPort;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class MerchantAuthentication implements MerchantAuthenticationPort {

  private final MerchantsService merchants;

  public MerchantAuthentication(MerchantsService merchants) {
    this.merchants = merchants;
  }

  @Override
  public Optional<AuthenticatedMerchant> authenticate(String rawBearerCredential) {
    return merchants.findByApiKey(rawBearerCredential)
        .map(merchant -> new AuthenticatedMerchant(merchant.publicId(), merchant.name()));
  }
}
