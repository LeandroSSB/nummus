package com.leandrossb.nummus.conciliation.interfaces;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.leandrossb.nummus.conciliation.application.ConciliationService;
import com.leandrossb.nummus.conciliation.application.UnknownConciliationReportException;
import com.leandrossb.nummus.conciliation.application.ConciliationStore;
import com.leandrossb.nummus.conciliation.interfaces.dto.CreateReportRequest;
import com.leandrossb.nummus.conciliation.interfaces.dto.ReportLineResponse;
import com.leandrossb.nummus.conciliation.interfaces.dto.ReportSummaryResponse;
import com.leandrossb.nummus.interfaces.auth.AuthenticatedOperator;
import com.leandrossb.nummus.interfaces.idempotency.Idempotent;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/conciliation/reports")
class ConciliationReportsController {

  private final ConciliationService conciliation;
  private final ConciliationStore store;

  ConciliationReportsController(ConciliationService conciliation, ConciliationStore store) {
    this.conciliation = conciliation;
    this.store = store;
  }

  @Idempotent
  @PostMapping
  ResponseEntity<ReportSummaryResponse> create(AuthenticatedOperator operator,
      @Valid @RequestBody CreateReportRequest request) {
    var summary = conciliation.ingest(Instant.parse(request.from()), Instant.parse(request.to()),
        operator.keyPublicId());
    return ResponseEntity
        .created(URI.create("/v1/conciliation/reports/" + summary.publicId()))
        .body(ReportSummaryResponse.from(summary));
  }

  @GetMapping
  List<ReportSummaryResponse> list(AuthenticatedOperator operator) {
    return store.listSummaries(50).stream().map(ReportSummaryResponse::from).toList();
  }

  @GetMapping("/{id}")
  ReportDetailResponse get(AuthenticatedOperator operator, @PathVariable UUID id) {
    var summary = store.findSummary(id).orElseThrow(() -> new UnknownConciliationReportException(id));
    return new ReportDetailResponse(ReportSummaryResponse.from(summary),
        store.findLines(id).stream().map(ReportLineResponse::from).toList());
  }

  record ReportDetailResponse(@JsonUnwrapped ReportSummaryResponse report,
      List<ReportLineResponse> lines) {
  }
}
