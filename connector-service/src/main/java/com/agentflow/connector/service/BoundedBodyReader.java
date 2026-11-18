package com.agentflow.connector.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 出网响应体的有界读取：超过上限立即放弃，并给整段读取设总时长上限。
 *
 * <p>两条约束都不能省：一次性的 {@code BodyHandlers.ofString()} 会在判断大小之前先把
 * 上游内容全吃进内存；仅靠 {@code HttpRequest.timeout} 又只约束到响应头，逐字节滴流
 * 的上游仍能把连接拖住（slow-loris）。故所有出网响应统一经本类读取。
 */
final class BoundedBodyReader {

    private static final int CHUNK_BYTES = 8 * 1024;
    private static final long MIN_BUDGET_MS = 1_000L;

    private BoundedBodyReader() {
    }

    /**
     * 读取至多 {@code maxBytes} 字节。
     *
     * @param in       上游响应流，由调用方负责关闭
     * @param maxBytes 允许的最大字节数
     * @param budget   整段读取的时间上限，小于 1s 时按 1s 计
     * @return 响应体；超出 {@code maxBytes} 时返回 {@code null}
     * @throws HttpTimeoutException 读取时间超出 {@code budget}
     * @throws IOException          上游连接异常
     */
    static byte[] read(InputStream in, int maxBytes, Duration budget) throws IOException {
        CompletableFuture<byte[]> reading = CompletableFuture.supplyAsync(() -> {
            try {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[CHUNK_BYTES];
                int read;
                while ((read = in.read(chunk)) != -1) {
                    if (buffer.size() + read > maxBytes) {
                        return null;
                    }
                    buffer.write(chunk, 0, read);
                }
                return buffer.toByteArray();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            return reading.get(Math.max(budget.toMillis(), MIN_BUDGET_MS), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            reading.cancel(true);
            closeQuietly(in);
            throw new HttpTimeoutException("upstream body read timed out");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof UncheckedIOException io) {
                throw io.getCause();
            }
            throw new IllegalStateException("upstream body read failed", e.getCause());
        } catch (InterruptedException e) {
            reading.cancel(true);
            Thread.currentThread().interrupt();
            throw new HttpTimeoutException("interrupted while reading upstream body");
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException ignored) {
            // 关闭失败不影响超时判定
        }
    }
}
