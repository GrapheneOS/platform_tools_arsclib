package org.grapheneos.arsclib;

public class Utils {

    public static RuntimeException asRuntimeException(Exception e) {
        if (e instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException(e);
    }

    public static String escapeYamlListItem(CharSequence s) {
        StringBuilder b = null;
        int prevPos = 0;
        for (int i = 0; i < s.length(); ++i) {
            char replacement;
            switch ((int) s.charAt(i)) {
                // https://yaml.org/spec/1.2.2/#57-escaped-characters
                case '\0': replacement = '0'; break;
                case 0x07: replacement = 'a'; break;
                case 0x08: replacement = 'b'; break;
                case '\t': replacement = 't'; break;
                case '\n': replacement = 'n'; break;
                case 0x0b: replacement = 'v'; break;
                case 0x0c: replacement = 'f'; break;
                case 0x0d: replacement = 'r'; break;
                case 0x1b: replacement = 'e'; break;
                case '"': replacement = '"'; break;
                case 0x5c: replacement = '\\'; break;
                case 0x85: replacement = 'N'; break;
                case 0xa0: replacement = '_'; break;
                case 0x2028: replacement = 'L'; break;
                case 0x2029: replacement = 'P'; break;
                case ' ':
                    if (i == 0 || i == s.length() - 1) {
                         replacement = ' ';
                         break;
                    }
                    continue;
                default:
                    continue;
            };

            if (b == null) {
                b = new StringBuilder(s.length() + 10);
                b.append('"');
            }

            b.append(s, prevPos, i);
            b.append('\\');
            b.append(replacement);
            prevPos = i + 1;
        }
        if (b == null) {
            return s.toString();
        }
        b.append(s, prevPos, s.length());
        b.append('"');
        return b.toString();
    }
}
