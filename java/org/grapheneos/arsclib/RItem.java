package org.grapheneos.arsclib;

import com.android.aapt.Resources;

import java.util.ArrayList;

import static com.google.common.base.Verify.verify;

class RItem {

    static String asString(Apk apk, Resources.Item item) {
        return switch (item.getValueCase()) {
            case REF -> RReference.asString(apk, item.getRef(), RReference.Context.OTHER);
            case STR -> maybeQuote(item.getStr().getValue());
            case STYLED_STR -> maybeQuote(styledStringToString(item.getStyledStr()));
            case PRIM -> Primitives.toString(item.getPrim());
            case ID, FILE, RAW_STR, VALUE_NOT_SET -> throw new IllegalArgumentException(item.toString());
        };
    }

    static String asRawString(Apk apk, Resources.Item item) {
        return switch (item.getValueCase()) {
            case REF -> RReference.asString(apk, item.getRef(), RReference.Context.OTHER);
            case STR -> item.getStr().getValue();
            case STYLED_STR -> styledStringToString(item.getStyledStr());
            case PRIM -> Primitives.toString(item.getPrim());
            case ID, FILE, RAW_STR, VALUE_NOT_SET -> throw new IllegalArgumentException(item.toString());
        };
    }

    static String maybeQuote(String s) {
        if (s.isEmpty()) {
            return s;
        }
        char first = s.charAt(0);
        boolean quote = first == ' ' || first == '@' || first == '?' || s.charAt(s.length() - 1) == ' ';
        if (!quote) {
            for (int i = 0; i < s.length(); ++i) {
                switch (s.charAt(i)) {
                    case '\n':
                    case '\t':
                    case '\'':
                    case '"':
                    case '\\':
                        quote = true;
                        break;
                }
            }
        }
        if (!quote) {
            return s;
        }
        var b = new StringBuilder(s.length() + 10);
        b.append('"');
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if (c == '\\' || c == '\"') {
                b.append('\\');
            }
            b.append(c);
        }
        b.append('"');
        return b.toString();
    }

    static String styledStringToString(Resources.StyledString value) {
        String base = value.getValue();
        int spanCount = value.getSpanCount();
        verify(spanCount > 0);

        var sb = new StringBuilder(base.length() + spanCount * 10);

        ArrayList<Resources.StyledString.Span> spans = new ArrayList<>(value.getSpanList());
        spans.sort((a, b) -> {
            int res = Integer.compare(a.getFirstChar(), b.getFirstChar());
            if (res == 0) {
                res = Integer.compare(b.getLastChar(), a.getLastChar());
                if (res == 0) {
                    res = a.getTag().compareTo(b.getTag());
                }
            }
            return res;
        });

        int strPos = 0;

        var stack = new ArrayList<Resources.StyledString.Span>(spans.size());

        for (int spanIdx = 0; spanIdx < spanCount; ++spanIdx) {
            Resources.StyledString.Span span = spans.get(spanIdx);
            sb.append(base, strPos, strPos = span.getFirstChar());

            String tag = span.getTag();
            String[] tokens = tag.split(";");
            verify(tokens.length >= 1, "token length %s", tokens.length);
            String tagName = tokens[0];
            {
                sb.append('<').append(tagName);
                for (int tokenIdx = 1; tokenIdx < tokens.length; ++tokenIdx) {
                    String kv = tokens[tokenIdx];
                    int splitIdx = kv.indexOf('=');
                    verify(splitIdx >= 1, tag);
                    sb.append(' ');
                    sb.append(kv, 0, splitIdx);
                    sb.append('=');
                    sb.append('"');
                    sb.append(kv, splitIdx + 1, kv.length());
                    sb.append('"');
                }
                sb.append('>');
            }

            Resources.StyledString.Span next = null;
            Resources.StyledString.Span nextNested = null;
            if (spanIdx != spanCount - 1) {
                next = spans.get(spanIdx + 1);
                if (next.getFirstChar() < span.getLastChar()) {
                    nextNested = next;
                }
            }

            if (nextNested != null) {
                stack.add(span);
                continue;
            }

            sb.append(base, strPos, strPos = span.getLastChar() + 1);
            sb.append("</").append(tagName).append('>');

            while (!stack.isEmpty()) {
                Resources.StyledString.Span prev = stack.getLast();
                if (next != null && next.getFirstChar() < prev.getLastChar()) {
                    break;
                }
                verify(prev.getLastChar() >= span.getLastChar());

                sb.append(base, strPos, strPos = prev.getLastChar() + 1);

                String tagInfo = prev.getTag();
                int splitIdx = tagInfo.indexOf(';');
                String prevTagName = splitIdx < 0 ? tagInfo : tagInfo.substring(0, splitIdx);
                sb.append("</").append(prevTagName).append('>');
                verify(stack.removeLast() == prev);
            }
        }
        verify(stack.isEmpty());

        verify(strPos <= base.length());
        if (strPos != base.length()) {
            sb.append(base, strPos, base.length());
        }

        return sb.toString();
    }
}
