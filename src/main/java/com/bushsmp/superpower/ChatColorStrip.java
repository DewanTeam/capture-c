package com.bushsmp.superpower;

public class ChatColorStrip {
    public static String stripColorCodes(String text) {
        if (text == null) return null;
        return text.replaceAll("&[0-9a-fk-or]", "").replaceAll("§[0-9a-fk-or]", "");
    }
}
