package com.leandrossb.nummus.conciliation.application;

import java.util.UUID;

/** A report line's identity: the subject kind plus the network instruction's
 *  public id — the matcher's map key and the schema's uniqueness key. */
public record SubjectRef(SubjectType type, UUID publicId) {
}
