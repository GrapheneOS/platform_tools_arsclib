package org.grapheneos.arsclib;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public class Filters {
    boolean include;
    Set<String> match = Collections.emptySet();
    List<String> prefixes = Collections.emptyList();
    List<String> suffixes = Collections.emptyList();
    List<String> substrings = Collections.emptyList();

    boolean test(String s) {
        boolean res = match.contains(s) || testPrefix(s) || testSuffix(s) || testSubstring(s);
        if (include) {
            // res = !res;
        }
        return res;
    }

    private boolean testPrefix(String s) {
        for (String prefix : prefixes) {
            if (s.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean testSuffix(String s) {
        for (String suffix : suffixes) {
            if (s.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private boolean testSubstring(String s) {
        for (String substring : substrings) {
            if (s.contains(substring)) {
                return true;
            }
        }
        return false;
    }
}
