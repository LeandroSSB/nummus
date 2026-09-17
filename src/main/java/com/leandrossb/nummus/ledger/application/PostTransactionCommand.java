package com.leandrossb.nummus.ledger.application;

import com.leandrossb.nummus.ledger.domain.PostingDraft;
import java.util.List;

/** Command to append a journal transaction. */
public record PostTransactionCommand(String memo, List<PostingDraft> postings) {
}
