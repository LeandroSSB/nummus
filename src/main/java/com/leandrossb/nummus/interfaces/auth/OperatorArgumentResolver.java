package com.leandrossb.nummus.interfaces.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** A handler parameter of type AuthenticatedOperator marks an operator route;
 *  reaching one without credentials is a 401, and reaching one authenticated
 *  as a merchant is a 403 role mismatch (both via the advice). */
public class OperatorArgumentResolver implements HandlerMethodArgumentResolver {

  @Override
  public boolean supportsParameter(MethodParameter parameter) {
    return parameter.getParameterType().equals(AuthenticatedOperator.class);
  }

  @Override
  public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
      NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
    HttpServletRequest request = webRequest.getNativeRequest(HttpServletRequest.class);
    Object operator = request == null ? null : request.getAttribute(MerchantAuthFilter.OPERATOR_ATTRIBUTE);
    if (operator == null) {
      if (request != null && request.getAttribute(MerchantAuthFilter.MERCHANT_ATTRIBUTE) != null) {
        throw new OperatorKeyRequiredException();
      }
      throw new OperatorUnauthorizedException();
    }
    return operator;
  }
}
