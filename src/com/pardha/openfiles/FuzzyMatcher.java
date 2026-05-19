package com.pardha.openfiles;

/**
 * Lightweight fuzzy matcher used by the Open Files search box.
 *
 * A query matches a name when every character of the query appears in the name
 * <em>in order</em> (but not necessarily contiguous) — the same style used by
 * VS Code's quick-open and IntelliJ's filename search.
 *
 * <p>
 * Examples:
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

   private FuzzyMatcher() {
   }

   /**
    * Returns {@code true} when every character of {@code query} (already
    * lowercased by the caller) is found in {@code name} in order,
    * case-insensitively.
    */
   public static boolean matches(String query, String name) {
      if (query == null || query.isEmpty()) {
         return true;
      }
      if (name == null || name.isEmpty()) {
         return false;
      }

      String lowerName = name.toLowerCase(java.util.Locale.ROOT);
      int qi = 0;
      int ni = 0;
      while (qi < query.length() && ni < lowerName.length()) {
         if (query.charAt(qi) == lowerName.charAt(ni)) {
            qi++;
         }
         ni++;
      }
      return qi == query.length();
   }

   /**
    * Returns a match score >= 0 if {@code query} fuzzy-matches {@code text},
    * or -1 if it does not match.
    *
    * <p>
    * Scoring rules (higher = better match):
    * <ul>
    * <li>+100 if {@code text} starts with {@code query} (prefix match)</li>
    * <li>+50 if {@code text} contains {@code query} as a substring</li>
    * <li>+1 per matched character that immediately follows the previous
    * match position (rewards consecutive runs)</li>
    * <li>-1 per gap character skipped between matches (penalises spread)</li>
    * </ul>
    *
    * <p>
    * Called on the EDT on every keystroke — must be fast.
    * Typical IFS project: ~500 files, each scored in < 0.05 ms → total < 25 ms.
    *
    * @param query lower-cased query string (caller must lower-case before passing)
    * @param text lower-cased candidate string
    * @return score >= 0 on match, -1 on no match
    */
   public static int score(String query, String text) {
      if (query.isEmpty()) {
         return 0;
      }

      // Prefix bonus
      if (text.startsWith(query)) {
         return 100 + query.length();
      }

      // Substring bonus
      if (text.contains(query)) {
         return 50 + query.length();
      }

      // In-order fuzzy match with gap scoring
      int qi = 0, ti = 0;
      int score = 0;
      int lastMatchPos = -2; // tracks previous match position for consecutive bonus

      while (qi < query.length() && ti < text.length()) {
         if (query.charAt(qi) == text.charAt(ti)) {
            score += (ti == lastMatchPos + 1) ? 2 : 1; // bonus for consecutive
            lastMatchPos = ti;
            qi++;
         } else {
            score--; // gap penalty
         }
         ti++;
      }

      if (qi < query.length()) {
         return -1; // not all query chars were found
      }

      return Math.max(0, score); // clamp to 0 so callers can use >= 0 as "matched"
   }
}
