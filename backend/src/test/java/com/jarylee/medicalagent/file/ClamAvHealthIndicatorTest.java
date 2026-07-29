package com.jarylee.medicalagent.file;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;

import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ClamAvHealthIndicatorTest {
    @Test
    void reportsUpWhenClamAvRespondsWithPong() throws Exception {
        try (var server = new ServerSocket(0);
             var executor = Executors.newSingleThreadExecutor()) {
            var serverTask = executor.submit(() -> {
                try (var socket = server.accept()) {
                    var input = socket.getInputStream();
                    while (input.read() != 0) {
                        // Consume the null-terminated PING command.
                    }
                    socket.getOutputStream().write("PONG\0".getBytes());
                    socket.getOutputStream().flush();
                }
                return null;
            });

            var indicator = new ClamAvHealthIndicator(
                    "127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2));

            assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
            serverTask.get();
        }
    }

    @Test
    void reportsDownWhenClamAvIsUnavailable() throws Exception {
        int unavailablePort;
        try (var server = new ServerSocket(0)) {
            unavailablePort = server.getLocalPort();
        }

        var indicator = new ClamAvHealthIndicator(
                "127.0.0.1", unavailablePort, Duration.ofMillis(200));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsDownForUnexpectedResponse() throws Exception {
        try (var server = new ServerSocket(0);
             var executor = Executors.newSingleThreadExecutor()) {
            var serverTask = executor.submit(() -> {
                try (var socket = server.accept()) {
                    var input = socket.getInputStream();
                    while (input.read() != 0) {
                        // Consume the null-terminated PING command.
                    }
                    socket.getOutputStream().write("UNKNOWN\0".getBytes());
                    socket.getOutputStream().flush();
                }
                return null;
            });

            var indicator = new ClamAvHealthIndicator(
                    "127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2));

            assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
            serverTask.get();
        }
    }
}
