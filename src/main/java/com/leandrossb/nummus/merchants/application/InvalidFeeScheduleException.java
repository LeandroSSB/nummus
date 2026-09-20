package com.leandrossb.nummus.merchants.application;

/** Raised for a fee schedule outside its valid range. */
public class InvalidFeeScheduleException extends RuntimeException {

  public InvalidFeeScheduleException(String message) {
    super(message);
  }
}
