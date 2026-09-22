package com.leandrossb.nummus.merchants.application;

/** A mint/bootstrap request carried an operator label that is blank or
 *  longer than 64 characters. */
public class InvalidOperatorLabelException extends RuntimeException {

  public InvalidOperatorLabelException() {
    super("label is required (1-64 characters)");
  }
}
