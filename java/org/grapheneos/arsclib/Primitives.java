package org.grapheneos.arsclib;

import com.android.aapt.Resources;

class Primitives {

    static String toString(Resources.Primitive p) {
        return switch (p.getOneofValueCase()) {
            case NULL_VALUE -> "@null";
            case EMPTY_VALUE -> "@empty";
            case FLOAT_VALUE -> Float.toString(p.getFloatValue());
            case DIMENSION_VALUE -> dimensionAsString(p.getDimensionValue());
            case FRACTION_VALUE -> fractionAsString(p.getFractionValue());
            case INT_DECIMAL_VALUE -> intAsString(p.getIntDecimalValue(), false);
            case INT_HEXADECIMAL_VALUE -> intAsString(p.getIntHexadecimalValue(), true);
            case BOOLEAN_VALUE -> Boolean.toString(p.getBooleanValue());
            case COLOR_ARGB8_VALUE -> colorToString(p.getColorArgb8Value());
            case COLOR_RGB8_VALUE -> colorToString(p.getColorRgb8Value());
            case COLOR_ARGB4_VALUE -> colorToString(p.getColorArgb4Value());
            case COLOR_RGB4_VALUE -> colorToString(p.getColorRgb4Value());
            case DIMENSION_VALUE_DEPRECATED, FRACTION_VALUE_DEPRECATED, ONEOFVALUE_NOT_SET ->
                    throw new IllegalArgumentException(p.toString());
        };
    }

    private static String dimensionAsString(int complex) {
        int unit = TypedValue.getUnitFromComplexDimension(complex);
        float val = TypedValue.complexToFloat(complex);

        String suffix = switch (unit) {
            case TypedValue.COMPLEX_UNIT_PX -> "px";
            case TypedValue.COMPLEX_UNIT_DIP -> "dp";
            case TypedValue.COMPLEX_UNIT_SP -> "sp";
            case TypedValue.COMPLEX_UNIT_PT -> "pt";
            case TypedValue.COMPLEX_UNIT_IN -> "in";
            case TypedValue.COMPLEX_UNIT_MM -> "mm";
            default -> throw new IllegalArgumentException(Integer.toHexString(complex));
        };
        return val + suffix;
    }

    private static String fractionAsString(int complex) {
        int unit = TypedValue.getUnitFromComplexDimension(complex);
        float val = TypedValue.complexToFloat(complex);

        String suffix = switch (unit) {
            case TypedValue.COMPLEX_UNIT_FRACTION -> "%";
            case TypedValue.COMPLEX_UNIT_FRACTION_PARENT -> "%p";
            default -> throw new IllegalArgumentException(Integer.toHexString(complex));
        };
        return (val * 100f) + suffix;
    }

    private static String intAsString(int value, boolean isHex) {
        return isHex? "0x" + Integer.toHexString(value) : Integer.toString(value);
    }

    private static final char[] HEX_CHARS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
    };

    private static String colorToString(int val) {
        char[] res = new char[9];
        res[0] = '#';

        int resIdx = 1;
        char[] hexChars = HEX_CHARS;
        for (int i = 0; i < 8; ++i) {
            int nibble = (val >> (28 - (i << 2))) & 0xf;
            res[resIdx++] = hexChars[nibble];
        }

        return new String(res);
    }

    static boolean isInt(Resources.Primitive prim) {
        return switch (prim.getOneofValueCase()) {
            case INT_DECIMAL_VALUE, INT_HEXADECIMAL_VALUE -> true;
            default -> false;
        };
    }

    static int getInt(Resources.Primitive prim) {
        return switch (prim.getOneofValueCase()) {
            case INT_DECIMAL_VALUE -> prim.getIntDecimalValue();
            case INT_HEXADECIMAL_VALUE -> prim.getIntHexadecimalValue();
            default -> throw new IllegalArgumentException(prim.toString());
        };
    }
}
