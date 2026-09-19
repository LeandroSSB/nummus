package com.leandrossb.nummus.interfaces.auth;

import java.util.UUID;

/** The authenticated operator, resolved from a Bearer operator key. Shared
 *  vocabulary: controllers declare this parameter to mark an operator route. */
public record AuthenticatedOperator(UUID keyPublicId) {
}
