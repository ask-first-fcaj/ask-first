package com.askfirst.common;

import java.security.SecureRandom;

/**
 * ULID 26 ký tự (Crockford base32): 10 ký tự thời gian (ms) + 16 ký tự ngẫu nhiên.
 * Sắp xếp theo chuỗi cũng là sắp xếp theo thời gian tạo.
 */
public final class Ulid {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private Ulid() {
    }

    public static String next() {
        return next(System.currentTimeMillis());
    }

    public static String next(long epochMillis) {
        if (epochMillis < 0 || epochMillis >= (1L << 48)) {
            throw new IllegalArgumentException("epochMillis ngoài khoảng 48 bit");
        }
        char[] out = new char[26];
        long t = epochMillis;
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (t & 31)];
            t >>>= 5;
        }
        for (int i = 10; i < 26; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(32)];
        }
        return new String(out);
    }
}
