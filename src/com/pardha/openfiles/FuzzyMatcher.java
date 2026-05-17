package com.pardha.openfiles;

/**
 * Lightweight fuzzy matcher used by the Open Files search box.
 *
 * A query matches a name when every character of the query appears in the name
 * <em>in order</em> (but not necessarily contiguous) — the same style used by
 * VS Code's quick-open and IntelliJ's filename search.
 *
 * <p>Examples:
 * <pre>
 *   "csivc"  matches "CScaleShipmentExecutionService-Cust.plsvc"  ✓
 *   "ent"    matches "CustomerOrder.entity"                        ✓
 *   "xyz"    matches "CustomerOrder.entity"                        ✗
 * </pre>
 *
 * Alphabetical ordering within matches is preserved by the caller; this class
 * only answers the boolean question "does it match?".
 */
public final class FuzzyMatcher {

    private FuzzyMatcher() {}

    /**
     * Returns {@code true} when every character of {@code query} (already
     * lowercased by the caller) is found in {@code name} in order,
     * case-insensitively.
     */
    public static boolean matches(String query, String name) {
        if (query == null || query.isEmpty()) return true;
        if (name  == null || name.isEmpty())  return false;

        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        int qi = 0;
        int ni = 0;
        while (qi < query.length() && ni < lowerName.length()) {
            if (query.charAt(qi) == lowerName.charAt(ni)) qi++;
            ni++;
        }
        return qi == query.length();
    }
}
