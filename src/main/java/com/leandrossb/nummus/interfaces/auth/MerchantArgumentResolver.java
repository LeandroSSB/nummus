package com.leandrossb.nummus.interfaces.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** A handler parameter of type AuthenticatedMerchant marks a merchant route;
 *  reaching one without authenticated credentials is a 401 (via the advice). */
public class MerchantArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType().equals(AuthenticatedMerchant.class);
  }

  @Override
  public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
    HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
    Object merchant = request == null ? null : request.getAttribute(MerchantAuthFilter.MERCHANT_ATTRIBUTE);
    if (merchant == null) {
      throw new MerchantUnauthorizedException();
    }
    return merchant;
  }
}
