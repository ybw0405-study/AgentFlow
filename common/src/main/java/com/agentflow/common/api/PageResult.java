package com.agentflow.common.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/** keyset 游标分页：cursor = base64url(lastSeq)。 */
public record PageResult<T>(List<T> items, String nextCursor) {

    public static <T> PageResult<T> of(List<T> items, Long lastSeq, boolean hasMore) {
        String cursor = hasMore && lastSeq != null ? encode(lastSeq) : null;
        return new PageResult<>(items, cursor);
    }

    public static String encode(long seq) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(seq).getBytes(StandardCharsets.UTF_8));
    }

    public static long decode(String cursor) {
        if (cursor == null || cursor.isBlank() || cursor.length() > 200) {
            return 0L;
        }
        try {
            return Long.parseLong(new String(
                    Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) { // 含 NumberFormatException
            return 0L;
        }
    }
}
