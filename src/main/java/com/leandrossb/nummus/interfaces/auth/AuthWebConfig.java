package com.leandrossb.nummus.interfaces.auth;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the auth argument resolvers. */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new MerchantArgumentResolver());
    resolvers.add(new OperatorArgumentResolver());
  }
}
