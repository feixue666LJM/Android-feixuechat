package com.feixue.chat.utils;

import java.util.Locale;

/** Converts the user-facing lowercase address code to a numeric IPv4 address. */
public final class ServerAddressCodec {
    private ServerAddressCodec() {
    }

    public static String decode(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("服务器地址不能为空");
        }
        String value = input.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.matches("[a-iz]{1,3}(\\.[a-iz]{1,3}){3}")) {
            validateNumericIfNeeded(value);
            return value;
        }
        if (!value.equals(lower)) {
            throw new IllegalArgumentException("IP替代代码必须全部使用小写字母");
        }
        StringBuilder decoded = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '.') {
                decoded.append('.');
            } else if (ch == 'z') {
                decoded.append('0');
            } else {
                decoded.append((char) ('1' + ch - 'a'));
            }
        }
        String address = decoded.toString();
        if (!isValidIpv4(address)) {
            throw new IllegalArgumentException("IP替代代码转换后的地址无效");
        }
        return address;
    }

    public static String displayForm(String input) {
        String value = input == null ? "" : input.trim();
        return isValidIpv4(value) ? encodeIpv4(value) : value;
    }

    public static String encodeIpv4(String address) {
        if (!isValidIpv4(address)) {
            throw new IllegalArgumentException("只能编码有效的IPv4地址");
        }
        StringBuilder encoded = new StringBuilder(address.length());
        for (int i = 0; i < address.length(); i++) {
            char ch = address.charAt(i);
            if (ch == '.') {
                encoded.append('.');
            } else if (ch == '0') {
                encoded.append('z');
            } else {
                encoded.append((char) ('a' + ch - '1'));
            }
        }
        return encoded.toString();
    }

    private static void validateNumericIfNeeded(String value) {
        if (value.matches("[0-9.]+") && !isValidIpv4(value)) {
            throw new IllegalArgumentException("数字IP格式无效");
        }
    }

    private static boolean isValidIpv4(String value) {
        if (value == null || !value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            return false;
        }
        for (String part : value.split("\\.")) {
            try {
                int number = Integer.parseInt(part);
                if (number < 0 || number > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }
}
