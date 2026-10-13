import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Locale;
import javax.net.ssl.HttpsURLConnection;

public final class NetworkProbe {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 2_000;

    private NetworkProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--download32".equals(args[0])) {
            download32MiB();
            return;
        }
        if (args.length == 0) {
            throw new IllegalArgumentException("At least one HTTPS URL is required");
        }

        boolean failed = false;
        for (String value : args) {
            URL url = URI.create(value).toURL();
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IllegalArgumentException("Only HTTPS probes are allowed");
            }

            Exception lastFailure = null;
            boolean complete = false;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                HttpsURLConnection connection = null;
                try {
                    connection = (HttpsURLConnection) url.openConnection();
                    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    connection.setReadTimeout(READ_TIMEOUT_MS);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("User-Agent", "UnifiedVPN-Android-TUN-Test/0.0.10");

                    int status = connection.getResponseCode();
                    try (InputStream stream = status >= 400
                            ? connection.getErrorStream()
                            : connection.getInputStream()) {
                        if (stream != null) {
                            stream.read();
                        }
                    }
                    if (status < 100 || status > 599) {
                        throw new IllegalStateException("Invalid HTTP response");
                    }
                    System.out.println(url.getHost() + "_http=" + status);
                    complete = true;
                    break;
                } catch (Exception failure) {
                    lastFailure = failure;
                    if (attempt < MAX_ATTEMPTS) {
                        Thread.sleep(RETRY_DELAY_MS);
                    }
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }

            if (!complete) {
                failed = true;
                String reason = lastFailure == null
                        ? "unknown"
                        : lastFailure.getClass().getSimpleName();
                if (lastFailure != null && lastFailure.getMessage() != null) {
                    String detail = lastFailure.getMessage()
                            .replace('\r', ' ')
                            .replace('\n', ' ')
                            .trim();
                    if (!detail.isEmpty()) {
                        reason += ":" + detail.substring(0, Math.min(detail.length(), 160));
                    }
                }
                System.err.println(url.getHost() + "_failed=" + reason);
            }
        }

        if (failed) {
            System.exit(2);
        }
    }

    private static void download32MiB() throws Exception {
        final long expected = 32L * 1024 * 1024;
        final long started = System.nanoTime();
        URL url = URI.create("https://speed.cloudflare.com/__down?bytes=" + expected).toURL();
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Connection", "close");
            connection.setUseCaches(false);
            if (connection.getResponseCode() != 200) {
                throw new IllegalStateException("Download did not return HTTP 200");
            }
            String encoding = connection.getContentEncoding();
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) {
                throw new IllegalStateException("Encoded download cannot measure throughput");
            }
            long length = connection.getContentLengthLong();
            if (length != -1 && length != expected) {
                throw new IllegalStateException("Unexpected download length");
            }
            long received = 0;
            byte[] buffer = new byte[65536];
            try (InputStream stream = connection.getInputStream()) {
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    received += count;
                    if (received > expected || System.nanoTime() - started > 80_000_000_000L) {
                        throw new IllegalStateException("Download exceeded byte or time limit");
                    }
                }
            }
            if (received != expected) {
                throw new IllegalStateException("Incomplete download");
            }
            double seconds = (System.nanoTime() - started) / 1_000_000_000.0;
            System.out.printf(Locale.ROOT, "ANDROID_DOWNLOAD_BYTES=%d ANDROID_DOWNLOAD_MBPS=%.4f TLS_VERIFIED=true%n",
                    received, received * 8.0 / seconds / 1_000_000.0);
        } finally {
            connection.disconnect();
        }
    }
}
