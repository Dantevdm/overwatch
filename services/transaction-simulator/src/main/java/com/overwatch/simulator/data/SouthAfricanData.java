package com.overwatch.simulator.data;

import java.util.List;
import java.util.Map;

/**
 * Reference data for the South African card landscape.
 *
 * <p>Real merchants, real banks, real cities, and spend ranges that reflect what
 * those merchants actually charge. This is the difference between a demo where the
 * rules visibly earn their keep and one where every row looks the same.
 */
public final class SouthAfricanData {

    private SouthAfricanData() {
    }

    /**
     * How often each category should appear in ordinary traffic, as relative
     * weights. They do not need to sum to anything in particular.
     *
     * <p>This exists because the obvious approach does not work. Picking a
     * merchant uniformly from {@link #MERCHANTS} makes a category's share equal
     * to <em>the number of merchants that happen to be listed under it</em>,
     * divided by the total — so groceries came out at exactly 6/36 and fuel at
     * 4/36, and the whole mix was an accident of how many shop names were typed
     * in rather than a statement about how people spend. Live traffic showed the
     * fingerprint clearly: categories clustered into flat bands at 11.1%, 8.3%
     * and 5.6%, which are just 4/36, 3/36 and 2/36.
     *
     * <p>Weighting the category first and the merchant second decouples the two,
     * so adding a shop no longer silently reweights the economy.
     */
    public static final Map<String, Integer> CATEGORY_WEIGHTS = Map.ofEntries(
            Map.entry("groceries", 22),    // the anchor of everyday card spend
            Map.entry("fuel", 14),
            Map.entry("restaurant", 11),
            Map.entry("retail", 11),
            Map.entry("ecommerce", 9),
            Map.entry("cash", 8),          // ATM withdrawals, still common here
            Map.entry("telecoms", 6),      // airtime and data, many small purchases
            Map.entry("transport", 6),
            Map.entry("pharmacy", 5),
            Map.entry("utilities", 5),
            Map.entry("liquor", 3));

    /** Everyday spend, grouped by category and weighted by {@link #CATEGORY_WEIGHTS}. */
    public static final List<Merchant> MERCHANTS = List.of(
            // Groceries — the bulk of ordinary card traffic
            Merchant.of("Checkers Brackenfell", "groceries", 120, 2400),
            Merchant.of("Pick n Pay Tygervalley", "groceries", 90, 1900),
            Merchant.of("Woolworths Constantia", "groceries", 150, 2800),
            Merchant.of("Shoprite Bellville", "groceries", 70, 1200),
            Merchant.of("SPAR Durbanville", "groceries", 80, 1500),
            Merchant.of("Food Lover's Market Claremont", "groceries", 100, 1600),

            // Liquor — the late-night rule's most common honest trigger
            Merchant.of("SPAR Liquor Centurion", "liquor", 120, 1400),
            Merchant.of("TOPS at SPAR Sea Point", "liquor", 150, 1800),
            Merchant.of("Ultra Liquors Milnerton", "liquor", 200, 2600),

            // Fuel
            Merchant.of("Engen Rivonia", "fuel", 350, 1500),
            Merchant.of("Shell Ultra City N1", "fuel", 400, 1600),
            Merchant.of("Sasol Bryanston", "fuel", 300, 1400),
            Merchant.of("BP Century City", "fuel", 380, 1500),

            // Restaurants
            Merchant.of("Nando's Sandton City", "restaurant", 95, 720),
            Merchant.of("Ocean Basket Gateway", "restaurant", 180, 950),
            Merchant.of("Spur Steak Ranches Canal Walk", "restaurant", 140, 880),
            Merchant.of("Steers Menlyn", "restaurant", 75, 460),

            // Pharmacy and health
            Merchant.of("Clicks Cavendish", "pharmacy", 60, 1400),
            Merchant.of("Dis-Chem Kolonnade", "pharmacy", 85, 2200),

            // Retail and e-commerce
            Merchant.of("Takealot Online", "ecommerce", 150, 6500),
            Merchant.of("Mr Price Eastgate", "retail", 120, 1800),
            Merchant.of("Game Kenilworth", "retail", 200, 5500),
            Merchant.of("Makro Woodmead", "retail", 350, 9000),
            Merchant.of("Superbalist Online", "ecommerce", 180, 3200),

            // Telecoms and utilities
            Merchant.of("Vodacom Prepaid", "telecoms", 29, 1100),
            Merchant.of("MTN Airtime", "telecoms", 29, 900),
            Merchant.of("Telkom Fibre", "telecoms", 599, 1899),
            Merchant.of("City of Cape Town Municipal", "utilities", 450, 4200),
            Merchant.of("Eskom Prepaid Electricity", "utilities", 200, 2500),

            // Transport
            Merchant.of("Uber Trip", "transport", 45, 480),
            Merchant.of("Bolt Ride", "transport", 38, 420),
            Merchant.of("Gautrain", "transport", 35, 200),

            // Banking
            Merchant.of("Capitec ATM Withdrawal", "cash", 100, 3000),
            Merchant.of("FNB ATM Bellville", "cash", 200, 4000),
            Merchant.of("Absa ATM Menlyn", "cash", 200, 3500),
            Merchant.of("Standard Bank ATM Umhlanga", "cash", 200, 3500));

    /**
     * Categories on the fraud watchlist. Kept separate from the everyday list so
     * ordinary traffic does not drift into them by accident — when one of these
     * appears it is because a fraud pattern was deliberately injected.
     */
    public static final List<Merchant> HIGH_RISK_MERCHANTS = List.of(
            Merchant.of("Luno Crypto Exchange", "crypto", 2000, 85000),
            Merchant.of("VALR Digital Assets", "crypto", 1500, 60000),
            Merchant.of("AltCoinTrader ZA", "crypto", 1000, 45000),
            Merchant.of("Hollywoodbets Online", "gambling", 100, 25000),
            Merchant.of("Betway South Africa", "gambling", 150, 30000),
            Merchant.of("Supabets Online", "gambling", 100, 18000),
            Merchant.of("FX Trading ZA", "forex", 5000, 120000));

    /** Issuing banks, used in transaction metadata. */
    public static final List<String> BANKS = List.of(
            "Capitec Bank", "First National Bank", "Absa", "Standard Bank",
            "Nedbank", "Discovery Bank", "TymeBank", "African Bank");

    public static final List<String> CITIES = List.of(
            "Cape Town", "Johannesburg", "Durban", "Pretoria", "Brackenfell",
            "Stellenbosch", "Bellville", "Sandton", "Centurion", "Gqeberha",
            "Bloemfontein", "Polokwane", "Umhlanga", "Somerset West");

    /**
     * Countries used for cross-border injection. Chosen because they appear in
     * genuine South African card-fraud reporting, not at random.
     */
    public static final List<String> FOREIGN_COUNTRIES = List.of(
            "NG", "GH", "RU", "CN", "UA", "RO", "BR", "IN");

    public static final String HOME_COUNTRY = "ZA";
}
