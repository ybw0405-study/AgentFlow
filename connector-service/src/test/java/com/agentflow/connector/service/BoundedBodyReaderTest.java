package com.agentflow.connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 出网响应体读取：正常、边界、超限、超时、上游异常。 */
class BoundedBodyReaderTest {

    private static final Duration BUDGET = Duration.ofSeconds(2);

    @Test
    void returnsWholeBodyWhenWithinLimit() throws Exception {
        byte[] payload = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

        byte[] body = BoundedBodyReader.read(new ByteArrayInputStream(payload), 1024, BUDGET);

        assertThat(body).isEqualTo(payload);
    }

    @Test
    void acceptsBodyExactlyAtLimit() throws Exception {
        byte[] payload = new byte[16];

        byte[] body = BoundedBodyReader.read(new ByteArrayInputStream(payload), payload.length, BUDGET);

        assertThat(body).hasSize(16);
    }

    @Test
    void abandonsBodyOneByteOverLimit() throws Exception {
        byte[] payload = new byte[17];

        byte[] body = BoundedBodyReader.read(new ByteArrayInputStream(payload), 16, BUDGET);

        assertThat(body).isNull();
    }

    @Test
    void abandonsBodyStreamedInManyChunks() throws Exception {
        byte[] payload = new byte[40 * 1024];

        byte[] body = BoundedBodyReader.read(new ByteArrayInputStream(payload), 8 * 1024, BUDGET);

        assertThat(body).isNull();
    }

    @Test
    void timesOutOnDripFeedAndClosesStream() {
        DripStream stream = new DripStream();

        assertThatThrownBy(() -> BoundedBodyReader.read(stream, 1024 * 1024, Duration.ofMillis(200)))
                .isInstanceOf(HttpTimeoutException.class);

        assertThat(stream.isClosed()).isTrue();
    }

    @Test
    void propagatesUpstreamFailure() {
        InputStream failing = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("upstream reset");
            }
        };

        assertThatThrownBy(() -> BoundedBodyReader.read(failing, 1024, BUDGET))
                .isInstanceOf(IOException.class)
                .hasMessage("upstream reset");
    }

    /** 每次只吐一个字节的慢速上游，用来触发总时长上限。 */
    private static final class DripStream extends InputStream {

        private volatile boolean closed;

        @Override
        public int read() throws IOException {
            if (closed) {
                throw new IOException("stream closed");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            return 'x';
        }

        @Override
        public void close() {
            closed = true;
        }

        boolean isClosed() {
            return closed;
        }
    }
}
