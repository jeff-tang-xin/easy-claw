package com.xinl.easyclaw.hub.service;

import java.security.SecureRandom;

/**
 * 临时密码生成：12 位，剔除易混淆字符（0/O、1/l/I），保证至少含一个字母与一个数字。
 */
public final class TempPasswords {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String LETTERS = "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String ALL = LETTERS + DIGITS;
    private static final int LENGTH = 12;

    private TempPasswords() {
    }

    public static String generate() {
        char[] a = new char[LENGTH];
        a[0] = LETTERS.charAt(RANDOM.nextInt(LETTERS.length()));
        a[1] = DIGITS.charAt(RANDOM.nextInt(DIGITS.length()));
        for (int i = 2; i < LENGTH; i++) {
            a[i] = ALL.charAt(RANDOM.nextInt(ALL.length()));
        }
        // 洗牌，避免「字母+数字」固定出现在开头
        for (int i = a.length - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
        return new String(a);
    }
}
