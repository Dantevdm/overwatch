package com.overwatch.api.customer;

import com.overwatch.api.customer.CustomerView.Profile;
import com.overwatch.api.customer.CustomerView.Summary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cardholders, and the 360 view of one.
 *
 * <p>Every rule here evaluates a single transaction, which is the right unit for
 * a real-time decision and the wrong one for an investigation. These endpoints
 * answer the question an analyst actually has when handed an alert: who is this,
 * and does this fit what they normally do.
 */
@RestController
@RequestMapping("/api/customers")
@Tag(name = "Cardholders", description = "The 360 view — one person, everything observed")
public class CustomerController {

    /** Page size ceiling, so a client cannot ask for the whole book in one call. */
    private static final int MAX_PAGE = 100;

    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @GetMapping
    @Operation(summary = "Cardholders observed, most active first",
            description = """
                    Derived from transactions rather than read from a customer
                    table, because there is no customer table: this system observes
                    a payment stream and does not own customer master data. A
                    cardholder with no transactions correctly does not appear.

                    `query` matches a substring of the display name or the
                    cardholder reference, case-insensitively — substring rather
                    than prefix because people search for a surname.""")
    public Page<Summary> search(
            @RequestParam(required = false)
            @Parameter(description = "Name or cardholder reference, any part of either.")
            String query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20")
            @Parameter(description = "Rows per page. Capped at 100.") int size) {
        return customers.search(query, PageRequest.of(Math.max(0, page), Math.clamp(size, 1, MAX_PAGE)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Everything observed about one cardholder",
            description = """
                    Their cards, spend by category, country and channel, the shape
                    of their day in SAST, every alert their transactions have
                    raised, and a behavioural risk summary.

                    The risk summary is a heuristic over observed behaviour and
                    says so: each signal is a share of this cardholder's own
                    history and carries the measurement behind it, so a line can be
                    discounted individually. A frequent traveller's foreign spend
                    is not fraud, and an investigator has to be able to see exactly
                    which number to ignore. It is not a model — a learned score
                    needs labelled outcomes, and no alert in this system has been
                    confirmed as fraud.

                    Answers 404 for a cardholder with no observed transactions.""")
    public Profile profile(@PathVariable String id) {
        return customers.profile(id);
    }

    @GetMapping("/{id}/summary")
    @Operation(summary = "The one-paragraph version, for a report header",
            description = """
                    The same profile reduced to the figures a report or an alert
                    triage screen needs, without the timelines. Separate from the
                    full profile so a caller that wants a headline does not pull a
                    cardholder's entire history to get it.""")
    public Map<String, Object> summary(@PathVariable String id) {
        Profile profile = customers.profile(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", profile.id());
        out.put("name", profile.name());
        out.put("homeCity", profile.homeCity());
        out.put("cards", profile.cards().size());
        out.put("transactions", profile.transactions());
        out.put("totalSpend", profile.totalSpend());
        out.put("averageAmount", profile.averageAmount());
        out.put("largestAmount", profile.largestAmount());
        out.put("firstSeen", profile.firstSeen());
        out.put("lastSeen", profile.lastSeen());
        out.put("alerts", profile.risk().alerts());
        out.put("riskBand", profile.risk().band());
        out.put("riskScore", profile.risk().score());
        return out;
    }
}
