import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public final class SocksUdpDnsProbe {
    private static final int TIMEOUT_MS = 15_000;

    private SocksUdpDnsProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException(
                    "Usage: SocksUdpDnsProbe proxyHost proxyPort username password domain");
        }

        String proxyHost = args[0];
        int proxyPort = Integer.parseInt(args[1]);
        String domain = args[4];
        int transactionId = new Random().nextInt(0x10000);
        byte[] query = dnsQuery(transactionId, domain);

        try (Socket control = new Socket()) {
            control.connect(new InetSocketAddress(proxyHost, proxyPort), TIMEOUT_MS);
            control.setSoTimeout(TIMEOUT_MS);
            InputStream input = control.getInputStream();
            OutputStream output = control.getOutputStream();
            authenticate(input, output, args[2], args[3]);
            InetSocketAddress relay = udpAssociate(input, output, proxyHost);

            try (DatagramSocket udp = new DatagramSocket()) {
                udp.setSoTimeout(TIMEOUT_MS);
                byte[] request = socksUdpPacket(query);
                udp.send(new DatagramPacket(request, request.length, relay));

                byte[] buffer = new byte[4096];
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                udp.receive(response);
                byte[] dns = extractDnsPayload(buffer, response.getLength());
                validateDnsResponse(dns, transactionId);
                int answerCount = ((dns[6] & 0xff) << 8) | (dns[7] & 0xff);
                System.out.println(domain + "_dns_answers=" + answerCount);
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
        if (readByte(input) != 0x05 || readByte(input) != 0x02) {
            throw new IOException("SOCKS5 username authentication is unavailable");
        }

        byte[] user = username.getBytes(StandardCharsets.UTF_8);
        byte[] pass = password.getBytes(StandardCharsets.UTF_8);
        output.write(0x01);
        output.write(user.length);
        output.write(user);
        output.write(pass.length);
        output.write(pass);
        output.flush();
        if (readByte(input) != 0x01 || readByte(input) != 0x00) {
            throw new IOException("SOCKS5 authentication failed");
        }
    }

    private static InetSocketAddress udpAssociate(
            InputStream input,
            OutputStream output,
            String fallbackHost
    ) throws IOException {
        output.write(new byte[] {0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        output.flush();
        if (readByte(input) != 0x05 || readByte(input) != 0x00) {
            throw new IOException("SOCKS5 UDP ASSOCIATE failed");
        }
        readByte(input);
        int type = readByte(input);
        String host;
        if (type == 0x01) {
            byte[] address = readFully(input, 4);
            host = InetAddress.getByAddress(address).getHostAddress();
        } else if (type == 0x03) {
            host = new String(readFully(input, readByte(input)), StandardCharsets.US_ASCII);
        } else if (type == 0x04) {
            byte[] address = readFully(input, 16);
            host = InetAddress.getByAddress(address).getHostAddress();
        } else {
            throw new IOException("Invalid SOCKS5 relay address type");
        }
        int port = (readByte(input) << 8) | readByte(input);
        if ("0.0.0.0".equals(host) || "::".equals(host)) {
            host = fallbackHost;
        }
        return new InetSocketAddress(host, port);
    }

    private static byte[] dnsQuery(int transactionId, String domain) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        output.write((transactionId >>> 8) & 0xff);
        output.write(transactionId & 0xff);
        output.write(new byte[] {0x01, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        for (String label : domain.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            if (bytes.length == 0 || bytes.length > 63) {
                throw new IllegalArgumentException("Invalid DNS name");
            }
            output.write(bytes.length);
            output.write(bytes);
        }
        output.write(0);
        output.write(new byte[] {0, 1, 0, 1});
        return output.toByteArray();
    }

    private static byte[] socksUdpPacket(byte[] dns) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        output.write(new byte[] {0, 0, 0, 1, 1, 1, 1, 1, 0, 53});
        output.write(dns);
        return output.toByteArray();
    }

    private static byte[] extractDnsPayload(byte[] packet, int length) throws IOException {
        if (length < 10 || packet[0] != 0 || packet[1] != 0 || packet[2] != 0) {
            throw new IOException("Invalid SOCKS5 UDP response");
        }
        int offset;
        int type = packet[3] & 0xff;
        if (type == 0x01) {
            offset = 10;
        } else if (type == 0x04) {
            offset = 22;
        } else if (type == 0x03 && length > 4) {
            offset = 7 + (packet[4] & 0xff);
        } else {
            throw new IOException("Invalid SOCKS5 UDP address type");
        }
        if (offset + 12 > length) {
            throw new IOException("Truncated DNS response");
        }
        byte[] result = new byte[length - offset];
        System.arraycopy(packet, offset, result, 0, result.length);
        return result;
    }

    private static void validateDnsResponse(byte[] dns, int transactionId) throws IOException {
        int responseId = ((dns[0] & 0xff) << 8) | (dns[1] & 0xff);
        int responseCode = dns[3] & 0x0f;
        if (responseId != transactionId || (dns[2] & 0x80) == 0 || responseCode != 0) {
            throw new IOException("Invalid DNS response");
        }
    }

    private static int readByte(InputStream input) throws IOException {
        int value = input.read();
        if (value < 0) {
            throw new EOFException("Unexpected end of SOCKS5 response");
        }
        return value;
    }

    private static byte[] readFully(InputStream input, int length) throws IOException {
        byte[] result = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(result, offset, length - offset);
            if (count < 0) {
                throw new EOFException("Unexpected end of SOCKS5 response");
            }
            offset += count;
        }
        return result;
    }
}
