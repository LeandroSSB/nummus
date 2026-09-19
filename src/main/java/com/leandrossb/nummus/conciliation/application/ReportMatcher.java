package com.leandrossb.nummus.conciliation.application;

import com.leandrossb.nummus.payments.application.SettlementView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pure matching: report lines against internal settled intents. Amount equality
 * is {@code Money.compareTo} (scale-insensitive). Internal settlements absent
 * from the report become MISSING_EXTERNAL lines of origin INTERNAL.
 */
public final class ReportMatcher {

  private ReportMatcher() {
  }

  public static MatchOutcome match(SettlementReport report, List<SettlementView> internal) {
    Map<UUID, SettlementView> byCharge = new HashMap<>();
    for (var view : internal) {
      byCharge.put(view.chargePublicId(), view);
    }
    Set<UUID> seenCharges = new HashSet<>();
    List<MatchedLine> lines = new ArrayList<>();
    int matched = 0;
    int mismatched = 0;
    int missingInternal = 0;
    for (var line : report.lines()) {
      if (!seenCharges.add(line.chargePublicId())) {
        throw new DuplicateSettlementLinesException(line.chargePublicId());
      }
      var view = byCharge.remove(line.chargePublicId());
      if (view == null) {
        lines.add(new MatchedLine("EXTERNAL", line.chargePublicId(), line.amount(),
            null, null, "MISSING_INTERNAL"));
        missingInternal++;
      } else if (view.amount().compareTo(line.amount()) == 0) {
        lines.add(new MatchedLine("EXTERNAL", line.chargePublicId(), line.amount(),
            view.intentPublicId(), view.amount(), "MATCHED"));
        matched++;
      } else {
        lines.add(new MatchedLine("EXTERNAL", line.chargePublicId(), line.amount(),
            view.intentPublicId(), view.amount(), "AMOUNT_MISMATCH"));
        mismatched++;
      }
    }
    // Whatever remains was settled internally inside the window but the network
    // never reported it.
    int missingExternal = 0;
    for (var view : byCharge.values()) {
      lines.add(new MatchedLine("INTERNAL", view.chargePublicId(), null,
          view.intentPublicId(), view.amount(), "MISSING_EXTERNAL"));
      missingExternal++;
    }
    boolean conciled = mismatched == 0 && missingInternal == 0 && missingExternal == 0;
    return new MatchOutcome(new MatchSummary(matched, mismatched, missingInternal,
        missingExternal, conciled), List.copyOf(lines));
  }

  /** Matching result: the persisted lines and their tally. */
  public record MatchOutcome(MatchSummary summary, List<MatchedLine> lines) {
  }
}
