package com.radolyn.ayugram;

import java.util.HashSet;
import java.util.Set;

/**
 * Telegram user IDs that get a badge next to their name inside THIS modified app only.
 * This is NOT real Telegram Premium: people using the official app will not see it.
 *
 * To add someone: write  BADGE_USER_IDS.add(123456789L);  inside the static block below.
 */
public class CustomBadge {

    private static final Set<Long> BADGE_USER_IDS = new HashSet<Long>();

    static {
        // BADGE_USER_IDS.add(123456789L);
        // BADGE_USER_IDS.add(987654321L);
    }

    public static boolean has(long userId) {
        return userId != 0 && BADGE_USER_IDS.contains(userId);
    }
}
