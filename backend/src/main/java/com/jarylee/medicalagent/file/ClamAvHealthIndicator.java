package com.jarylee.medicalagent.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
@ConditionalOnProperty(name = "medical.file-scan.mode", havingValue = "clamav")
public class ClamAvHealthIndicator implements HealthIndicator {
    private static final int MAX_RESPONSE_BYTES = 32;

    private final String host;
    private final int port;
    private final Duration timeout;

    public ClamAvHealthIndicator(
            @Value("${medical.file-scan.clamav-host:127.0.0.1}") String host,
            @Value("${medical.file-scan.clamav-port:3310}") int port,
            @Value("${medical.file-scan.timeout:5s}") Duration timeout) {
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    @Override
    public Health health() {
        int timeoutMillis = Math.toIntExact(timeout.toMillis());
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            socket.getOutputStream().write("zPING\0".getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return "PONG".equals(readResponse(socket)) ? Health.up().build() : Health.down().build();
        } catch (Exception exception) {
            return Health.down().build();
        }
    }

    private String readResponse(Socket socket) throws Exception {
        var response = new ByteArrayOutputStream();
        var input = socket.getInputStream();
        while (response.size() < MAX_RESPONSE_BYTES) {
            int value = input.read();
            if (value < 0 || value == 0) {
                break;
            }
            response.write(value);
        }
        if (response.size() == MAX_RESPONSE_BYTES) {
            return "";
        }
        return response.toString(StandardCharsets.US_ASCII).trim();
    }
}
