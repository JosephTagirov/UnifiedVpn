import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

public final class NetworkProbe {
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MS = 2_000;

    private NetworkProbe() {
    }

    public static void main(String[] args) throws Exception {
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
}
