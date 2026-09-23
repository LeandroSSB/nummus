package com.leandrossb.nummus.conciliation.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure matching: report lines against internal settlements of every subject
 * kind. Amount equality is {@code Money.compareTo} (scale-insensitive). Internal
 * settlements absent from the report become MISSING_EXTERNAL lines of origin
 * INTERNAL.
 */
public final class ReportMatcher {

  private ReportMatcher() {
  }

  public static MatchOutcome match(SettlementReport report, List<InternalSettlement> internal) {
    Map<SubjectRef, InternalSettlement> bySubject = new HashMap<>();
    for (var view : internal) {
      bySubject.put(new SubjectRef(view.subjectType(), view.subjectPublicId()), view);
    }
    Set<SubjectRef> seenSubjects = new HashSet<>();
    List<MatchedLine> lines = new ArrayList<>();
    int matched = 0;
    int mismatched = 0;
    int missingInternal = 0;
    for (var line : report.lines()) {
      var subject = new SubjectRef(line.subjectType(), line.subjectPublicId());
      if (!seenSubjects.add(subject)) {
        throw new DuplicateSettlementLinesException(line.subjectPublicId());
      }
      var view = bySubject.remove(subject);
      if (view == null) {
        lines.add(new MatchedLine("EXTERNAL", line.subjectType(), line.subjectPublicId(),
            line.amount(), null, null, "MISSING_INTERNAL"));
        missingInternal++;
      } else if (view.amount().compareTo(line.amount()) == 0) {
        lines.add(new MatchedLine("EXTERNAL", line.subjectType(), line.subjectPublicId(),
            line.amount(), view.internalPublicId(), view.amount(), "MATCHED"));
        matched++;
      } else {
        lines.add(new MatchedLine("EXTERNAL", line.subjectType(), line.subjectPublicId(),
            line.amount(), view.internalPublicId(), view.amount(), "AMOUNT_MISMATCH"));
        mismatched++;
      }
    }
    // Whatever remains was settled internally inside the window but the network
    // never reported it.
    int missingExternal = 0;
    for (var view : bySubject.values()) {
      lines.add(new MatchedLine("INTERNAL", view.subjectType(), view.subjectPublicId(), null,
          view.internalPublicId(), view.amount(), "MISSING_EXTERNAL"));
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
