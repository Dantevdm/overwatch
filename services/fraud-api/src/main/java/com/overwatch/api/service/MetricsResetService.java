package com.overwatch.api.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Clears this stack's own metrics out of Prometheus.
 *
 * <p>Offered alongside the data reset, because a cleared store beside a Grafana
 * dashboard still showing the run that was cleared is a confusing pair of screens
 * to hand somebody. Both are correct — they answer different questions — but
 * "start from nothing" should be able to mean all of it.
 *
 * <p>Deleting the series is deliberately not the same thing as resetting the
 * counters, and it is the right one. A Micrometer counter is monotonic by
 * contract; forcing one back to zero makes every {@code rate()} over the
 * boundary read as an enormous negative or an enormous spike depending on which
 * side of it the query lands. Removing the series instead leaves nothing to
 * compute a rate across: the panels are empty until new scrapes arrive, which is
 * exactly what "cleared" should look like. The application's counters are left
 * alone and keep counting, so nothing in the running system is lied to.
 *
 * <p>Bounded by a matcher on this stack's own jobs. The Prometheus TSDB admin API
 * will happily delete everything it holds if handed {@code {__name__!=""}}, and
 * the one thing this must not do is take out an unrelated tenant's data because
 * somebody pointed the demo at a shared Prometheus.
 */
@Service
public class MetricsResetService {

    private static final Logger log = LoggerFactory.getLogger(MetricsResetService.class);

    /**
     * The jobs this stack owns, from prometheus.yml. Series belonging to anything
     * else are not this endpoint's to delete.
     *
     * <p>The {@code prometheus} job itself is excluded on purpose: it is
     * Prometheus scraping its own health, it says nothing about the fraud
     * pipeline, and deleting it would blind the one thing that could tell you the
     * scrape had stopped working.
     */
    private static final List<String> OWNED_JOBS =
            List.of("fraud-engine", "fraud-api", "transaction-simulator");

    private final RestClient client;
    private final boolean allowed;
    private final String baseUrl;

    public MetricsResetService(
            @Value("${overwatch.api.prometheus-url:http://prometheus:9090}") String baseUrl,
            @Value("${overwatch.api.allow-metrics-reset:true}") boolean allowed) {
        this.baseUrl = baseUrl;
        this.allowed = allowed;
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        if (allowed) {
            log.info("Metrics reset enabled against {}", baseUrl);
        } else {
            log.info("Metrics reset is disabled (overwatch.api.allow-metrics-reset=false)");
        }
    }

    public boolean isAllowed() {
        return allowed;
    }

    /** What the reset endpoint reports back about the metrics half of the job. */
    public record Outcome(boolean cleared, String detail) {
    }

    /**
     * Delete this stack's series, then compact away the tombstones.
     *
     * <p>Both calls matter. {@code delete_series} marks the data deleted and it
     * stops being returned by queries immediately, but it stays on disk until
     * {@code clean_tombstones} runs or a compaction happens to cover it — so
     * without the second call the space is not reclaimed, which on a demo stack
     * that gets cleared repeatedly is the difference between a tidy volume and a
     * growing one.
     *
     * <p>Never throws. A metrics reset failing is not a reason for a data reset to
     * report failure: the destructive part already succeeded, and the caller needs
     * to be told what did and did not happen rather than handed a 500.
     */
    public Outcome reset() {
        if (!allowed) {
            return new Outcome(false,
                    "Metrics reset is disabled (overwatch.api.allow-metrics-reset=false).");
        }
        try {
            // A fully-built, already-encoded absolute URI rather than a path with
            // query parameters. A PromQL selector is wrapped in braces, and every
            // URI builder in Spring reads braces as template variables -- so
            // handing this one {job=~"..."} through queryParam() fails with "not
            // enough variable values available to expand", naming the selector as
            // if it were a placeholder somebody forgot to fill in.
            client.post().uri(deleteSeriesUri()).retrieve().toBodilessEntity();
            client.post().uri(URI.create(baseUrl + "/api/v1/admin/tsdb/clean_tombstones"))
                    .retrieve().toBodilessEntity();

            log.warn("Metrics reset: deleted every series for jobs {} from {}",
                    OWNED_JOBS, baseUrl);
            return new Outcome(true,
                    "Deleted every series for " + String.join(", ", OWNED_JOBS)
                    + ". Grafana panels will be empty until the next scrape.");
        } catch (RuntimeException e) {
            // RuntimeException, not RestClientException. This method's contract is
            // that it never throws, because by the time it runs the rows are
            // already deleted and a caller told "reset failed" would reasonably
            // try again. Catching only the HTTP exception type left a URI-building
            // bug free to propagate as a 500 from an endpoint that had already
            // succeeded at the destructive half of its job -- which is exactly
            // the failure the contract exists to prevent.
            //
            // The likeliest genuine cause is Prometheus running without
            // --web.enable-admin-api, which answers 404. Saying so is more use
            // than the status code, because the fix is one compose line.
            log.warn("Could not reset metrics against {}: {}", baseUrl, e.toString());
            return new Outcome(false,
                    "Could not clear the metrics — Prometheus may be running "
                    + "without --web.enable-admin-api. Details: " + e.getMessage());
        }
    }

    /**
     * The delete call, with the selector percent-encoded by hand.
     *
     * <p>Assembled as a URI rather than left to a builder, for the brace reason
     * above. {@code match[]} is Prometheus's own parameter name, brackets
     * included.
     */
    private URI deleteSeriesUri() {
        return URI.create(baseUrl + "/api/v1/admin/tsdb/delete_series?match%5B%5D="
                + URLEncoder.encode(matcher(), StandardCharsets.UTF_8));
    }

    /**
     * {@code {job=~"fraud-engine|fraud-api|transaction-simulator"}} — every series
     * carrying one of this stack's job labels, and nothing else.
     */
    private static String matcher() {
        return "{job=~\"" + String.join("|", OWNED_JOBS) + "\"}";
    }
}
