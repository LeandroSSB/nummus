package com.leandrossb.nummus.conciliation.infrastructure;

import com.leandrossb.nummus.conciliation.application.ConciliationStore;
import com.leandrossb.nummus.conciliation.application.MatchedLine;
import com.leandrossb.nummus.conciliation.application.SettlementReportSummary;
import com.leandrossb.nummus.ledger.domain.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientConciliationStore implements ConciliationStore {

  private static final Currency BRL = Currency.getInstance("BRL");

  private final JdbcClient jdbc;

  public JdbcClientConciliationStore(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(SettlementReportSummary summary, List<MatchedLine> lines) {
    jdbc.sql("""
        insert into conciliation.settlement_report
          (public_id, period_from, period_to, status, matched_count,
           amount_mismatched_count, missing_internal_count, missing_external_count, created_at)
        values (:publicId, :from, :to, :status, :matched, :mismatched, :missingInternal,
                :missingExternal, :createdAt)
        """)
        .param("publicId", summary.publicId())
        .param("from", toOffsetDateTime(summary.from()))
        .param("to", toOffsetDateTime(summary.to()))
        .param("status", summary.status())
        .param("matched", summary.matched())
        .param("mismatched", summary.amountMismatched())
        .param("missingInternal", summary.missingInternal())
        .param("missingExternal", summary.missingExternal())
        .param("createdAt", toOffsetDateTime(summary.createdAt()))
        .update();
    Long reportRowId = jdbc.sql(
        "select id from conciliation.settlement_report where public_id = :publicId")
        .param("publicId", summary.publicId())
        .query(Long.class).single();
    for (var line : lines) {
      jdbc.sql("""
          insert into conciliation.report_line
            (report_id, origin, charge_public_id, reported_amount,
             internal_intent_public_id, internal_amount, match_status)
          values (:reportId, :origin, :chargeId, :reported, :intentId, :internal, :status)
          """)
          .param("reportId", reportRowId)
          .param("origin", line.origin())
          .param("chargeId", line.chargePublicId())
          .param("reported", line.reportedAmount() == null ? null : line.reportedAmount().amount())
          .param("intentId", line.internalIntentPublicId())
          .param("internal", line.internalAmount() == null ? null : line.internalAmount().amount())
          .param("status", line.matchStatus())
          .update();
    }
  }

  @Override
  public List<SettlementReportSummary> listSummaries(int limit) {
    return jdbc.sql("""
        select public_id, period_from, period_to, status, matched_count,
               amount_mismatched_count, missing_internal_count, missing_external_count, created_at
        from conciliation.settlement_report order by id desc limit :limit
        """)
        .param("limit", limit)
        .query((rs, i) -> mapSummary(rs)).list();
  }

  @Override
  public Optional<SettlementReportSummary> findSummary(UUID publicId) {
    return jdbc.sql("""
        select public_id, period_from, period_to, status, matched_count,
               amount_mismatched_count, missing_internal_count, missing_external_count, created_at
        from conciliation.settlement_report where public_id = :publicId
        """)
        .param("publicId", publicId)
        .query((rs, i) -> mapSummary(rs)).optional();
  }

  @Override
  public List<MatchedLine> findLines(UUID reportPublicId) {
    return jdbc.sql("""
        select l.origin, l.charge_public_id, l.reported_amount,
               l.internal_intent_public_id, l.internal_amount, l.match_status
        from conciliation.report_line l
        join conciliation.settlement_report r on r.id = l.report_id
        where r.public_id = :reportPublicId
        order by l.id
        """)
        .param("reportPublicId", reportPublicId)
        .query((rs, i) -> new MatchedLine(rs.getString(1), rs.getObject(2, UUID.class),
            rs.getObject(3) == null ? null : Money.of(rs.getBigDecimal(3), BRL),
            rs.getObject(4, UUID.class),
            rs.getObject(5) == null ? null : Money.of(rs.getBigDecimal(5), BRL),
            rs.getString(6)))
        .list();
  }

  private static SettlementReportSummary mapSummary(ResultSet rs) throws SQLException {
    return new SettlementReportSummary(rs.getObject("public_id", UUID.class),
        rs.getObject("period_from", OffsetDateTime.class).toInstant(),
        rs.getObject("period_to", OffsetDateTime.class).toInstant(),
        rs.getString("status"), rs.getInt("matched_count"),
        rs.getInt("amount_mismatched_count"), rs.getInt("missing_internal_count"),
        rs.getInt("missing_external_count"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }

  private static OffsetDateTime toOffsetDateTime(Instant instant) {
    return instant.atOffset(ZoneOffset.UTC);
  }
}
