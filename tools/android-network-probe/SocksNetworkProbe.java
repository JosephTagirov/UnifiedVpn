import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

public final class SocksNetworkProbe {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 2_000;

    private SocksNetworkProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 5) {
            throw new IllegalArgumentException(
                    "Usage: SocksNetworkProbe proxyHost proxyPort username password httpsUrl...");
        }

        String proxyHost = args[0];
        int proxyPort = Integer.parseInt(args[1]);
        String username = args[2];
        String password = args[3];
        boolean failed = false;

        for (int index = 4; index < args.length; index++) {
            URL url = URI.create(args[index]).toURL();
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IllegalArgumentException("Only HTTPS probes are allowed");
            }

            Exception lastFailure = null;
            boolean complete = false;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    int status = request(proxyHost, proxyPort, username, password, url);
                    System.out.println(url.getHost() + "_socks_http=" + status);
                    complete = true;
                    break;
                } catch (Exception failure) {
                    lastFailure = failure;
                    if (attempt < MAX_ATTEMPTS) {
                        Thread.sleep(RETRY_DELAY_MS);
                    }
                }
            }

            if (!complete) {
                failed = true;
                System.err.println(url.getHost() + "_socks_failed=" + failureSummary(lastFailure));
            }
        }

        if (failed) {
            System.exit(2);
        }
    }

    private static int request(
            String proxyHost,
            int proxyPort,
            String username,
            String password,
            URL url
    ) throws Exception {
        try (Socket proxy = new Socket()) {
            proxy.connect(new InetSocketAddress(proxyHost, proxyPort), CONNECT_TIMEOUT_MS);
            proxy.setSoTimeout(READ_TIMEOUT_MS);
            InputStream input = new BufferedInputStream(proxy.getInputStream());
            OutputStream output = new BufferedOutputStream(proxy.getOutputStream());

            authenticate(input, output, username, password);
            connect(input, output, url.getHost(), url.getPort() > 0 ? url.getPort() : 443);

            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket tls = (SSLSocket) factory.createSocket(proxy, url.getHost(), 443, false)) {
                tls.setSoTimeout(READ_TIMEOUT_MS);
                tls.startHandshake();

                OutputStream tlsOutput = new BufferedOutputStream(tls.getOutputStream());
                String path = url.getFile().isEmpty() ? "/" : url.getFile();
                String request = "GET " + path + " HTTP/1.1\r\n"
                        + "Host: " + url.getHost() + "\r\n"
                        + "User-Agent: UnifiedVPN-Android-SOCKS-Test/0.0.10\r\n"
                        + "Connection: close\r\n\r\n";
                tlsOutput.write(request.getBytes(StandardCharsets.US_ASCII));
                tlsOutput.flush();

                String statusLine = readLine(tls.getInputStream());
                String[] parts = statusLine.split(" ", 3);
                if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
                    throw new IOException("Invalid HTTP response");
                }
                int status = Integer.parseInt(parts[1]);
                if (status < 100 || status > 599) {
                    throw new IOException("Invalid HTTP status");
                }
                return status;
            }
        }
    }

    private static void authenticate(
            InputStream input,
            OutputStream output,
            String username,
            String password
    ) throws IOException {
        output.write(new byte[] {0x05, 0x01, 0x02});
        output.flush();
        int version = readByte(input);
        int method = readByte(input);
        if (version != 0x05 || method != 0x02) {
            throw new IOException("SOCKS5 username authentication is unavailable");
        }

        byte[] userBytes = username.getBytes(StandardCharsets.UTF_8);
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
        if (userBytes.length > 255 || passwordBytes.length > 255) {
            throw new IllegalArgumentException("SOCKS5 credentials are too long");
        }

        output.write(0x01);
        output.write(userBytes.length);
        output.write(userBytes);
        output.write(passwordBytes.length);
        output.write(passwordBytes);
        output.flush();
        if (readByte(input) != 0x01 || readByte(input) != 0x00) {
            throw new IOException("SOCKS5 authentication failed");
        }
    }

    private static void connect(
            InputStream input,
            OutputStream output,
            String host,
            int port
    ) throws IOException {
        byte[] hostBytes = host.getBytes(StandardCharsets.US_ASCII);
        if (hostBytes.length > 255) {
            throw new IllegalArgumentException("Target hostname is too long");
        }

        output.write(new byte[] {0x05, 0x01, 0x00, 0x03});
        output.write(hostBytes.length);
        output.write(hostBytes);
        output.write((port >>> 8) & 0xff);
        output.write(port & 0xff);
        output.flush();

        if (readByte(input) != 0x05) {
            throw new IOException("Invalid SOCKS5 response");
        }
        int status = readByte(input);
        readByte(input);
        int addressType = readByte(input);
        if (status != 0x00) {
            throw new IOException("SOCKS5 connect failed with status " + status);
        }

        int addressLength;
        if (addressType == 0x01) {
            addressLength = 4;
        } else if (addressType == 0x03) {
            addressLength = readByte(input);
        } else if (addressType == 0x04) {
            addressLength = 16;
        } else {
            throw new IOException("Invalid SOCKS5 address type");
        }
        readFully(input, addressLength + 2);
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Unexpected end of SOCKS5 response");
        }
        return value;
    }

    private static void readFully(InputStream input, int length) throws IOException {
        for (int index = 0; index < length; index++) {
            readByte(input);
        }
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (line.size() < 4_096) {
            int value = input.read();
            if (value < 0) {
                throw new EOFException("HTTP response closed before status line");
            }
            if (previous == '\r' && value == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
            }
            line.write(value);
            previous = value;
        }
        throw new IOException("HTTP status line is too long");
    }

    private static String failureSummary(Exception failure) {
        if (failure == null) {
            return "unknown";
        }
        String summary = failure.getClass().getSimpleName();
        String message = failure.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return summary;
        }
        String detail = message.replace('\r', ' ').replace('\n', ' ').trim();
        return summary + ":" + detail.substring(0, Math.min(detail.length(), 160));
    }
}
