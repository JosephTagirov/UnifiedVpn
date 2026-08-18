package org.olcbox.app.provisioning

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.util.encoders.Base64
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom

data class SelfHostedServer(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String
) {
    fun normalized(): SelfHostedServer = copy(
        host = host.trim().removePrefix("[").removeSuffix("]"),
        username = username.trim()
    )

    fun validate() {
        require(host.isNotBlank()) { "Server address is required" }
        require(host.none { it.isWhitespace() || it.isISOControl() }) {
            "Server address must not contain spaces or control characters"
        }
        require(port in 1..65535) { "SSH port must be between 1 and 65535" }
        require(username.isNotBlank()) { "SSH login is required" }
        require(password.isNotEmpty()) { "SSH password is required" }
    }
}

data class SshHostIdentity(
    val algorithm: String,
    val publicKeyBase64: String,
    val fingerprint: String
)

class SelfHostedProvisioner {
    suspend fun inspectHost(server: SelfHostedServer): SshHostIdentity = runInterruptible(Dispatchers.IO) {
        val normalized = server.normalized().also { it.validate() }
        val session = createSession(normalized, strictHostKeyChecking = false)
        try {
            session.connect(CONNECT_TIMEOUT_MS)
            val hostKey = session.hostKey ?: error("SSH server did not provide a host key")
            SshHostIdentity(
                algorithm = hostKey.type,
                publicKeyBase64 = hostKey.key,
                fingerprint = sha256Fingerprint(hostKey.key)
            )
        } finally {
            session.disconnect()
        }
    }

    suspend fun provisionAmneziaWg(
        server: SelfHostedServer,
        trustedHost: SshHostIdentity,
        onProgress: (String) -> Unit = {}
    ): String = runInterruptible(Dispatchers.IO) {
        val normalized = server.normalized().also { it.validate() }
        val keyPair = generateWireGuardKeyPair()
        val jsch = JSch().apply {
            val knownHost = knownHostLine(normalized, trustedHost)
            setKnownHosts(ByteArrayInputStream(knownHost.toByteArray(Charsets.US_ASCII)))
        }
        val session = createSession(normalized, strictHostKeyChecking = true, jsch = jsch)
        var channel: ChannelExec? = null
        try {
            onProgress("Connecting over SSH")
            session.connect(CONNECT_TIMEOUT_MS)
            verifyHostKey(session, trustedHost)

            channel = session.openChannel("exec") as ChannelExec
            channel.setCommand("sh -s")
            channel.setPty(false)
            val stdout = channel.inputStream
            val stderr = TailOutputStream(MAX_ERROR_LENGTH)
            channel.setErrStream(stderr)
            val stdin = channel.outputStream
            channel.connect(CONNECT_TIMEOUT_MS)

            val script = PROVISION_SCRIPT
                .replace(CLIENT_PUBLIC_KEY_PLACEHOLDER, keyPair.publicKey)
                .replace(SUDO_PASSWORD_PLACEHOLDER, shellSingleQuotedContent(normalized.password))
            stdin.use { stream ->
                stream.write(script.toByteArray(Charsets.UTF_8))
                stream.flush()
            }

            val result = linkedMapOf<String, String>()
            stdout.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.startsWith(STAGE_PREFIX) -> onProgress(line.removePrefix(STAGE_PREFIX).trim())
                        line.startsWith(RESULT_PREFIX) -> {
                            val value = line.removePrefix(RESULT_PREFIX).trim()
                            val separator = value.indexOf('=')
                            if (separator > 0) {
                                result[value.substring(0, separator)] = value.substring(separator + 1)
                            }
                        }
                        line.startsWith(ERROR_PREFIX) -> error(line.removePrefix(ERROR_PREFIX).trim())
                    }
                }
            }

            while (!channel.isClosed) Thread.sleep(CHANNEL_POLL_MS)
            if (channel.exitStatus != 0) {
                val details = stderr.text().trim()
                error(details.ifBlank { "Server setup failed with code ${channel.exitStatus}" })
            }

            buildClientConfig(normalized.host, keyPair.privateKey, result)
        } finally {
            channel?.disconnect()
            session.disconnect()
        }
    }

    private fun createSession(
        server: SelfHostedServer,
        strictHostKeyChecking: Boolean,
        jsch: JSch = JSch()
    ): Session {
        return jsch.getSession(server.username, server.host, server.port).apply {
            setPassword(server.password)
            setConfig("StrictHostKeyChecking", if (strictHostKeyChecking) "yes" else "no")
            setConfig("PreferredAuthentications", "password,keyboard-interactive")
            setConfig("MaxAuthTries", "2")
            serverAliveInterval = SERVER_ALIVE_INTERVAL_MS
            serverAliveCountMax = SERVER_ALIVE_COUNT
        }
    }

    private fun verifyHostKey(session: Session, trustedHost: SshHostIdentity) {
        val actual = session.hostKey ?: error("SSH server did not provide a host key")
        val expectedBytes = Base64.decode(trustedHost.publicKeyBase64)
        val actualBytes = Base64.decode(actual.key)
        require(actual.type == trustedHost.algorithm && MessageDigest.isEqual(actualBytes, expectedBytes)) {
            "SSH host key changed after confirmation"
        }
    }

    private fun knownHostLine(server: SelfHostedServer, identity: SshHostIdentity): String {
        val host = if (server.port == DEFAULT_SSH_PORT) server.host else "[${server.host}]:${server.port}"
        return "$host ${identity.algorithm} ${identity.publicKeyBase64}\n"
    }

    private fun sha256Fingerprint(publicKeyBase64: String): String {
        val key = Base64.decode(publicKeyBase64)
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        val encoded = Base64.toBase64String(digest).trimEnd('=')
        return "SHA256:$encoded"
    }

    private fun generateWireGuardKeyPair(): WireGuardKeyPair {
        val privateKey = X25519PrivateKeyParameters(SecureRandom())
        val publicKey = privateKey.generatePublicKey()
        return WireGuardKeyPair(
            privateKey = Base64.toBase64String(privateKey.encoded),
            publicKey = Base64.toBase64String(publicKey.encoded)
        )
    }

    private fun shellSingleQuotedContent(value: String): String {
        return value.replace("'", "'\"'\"'")
    }

    private fun buildClientConfig(
        host: String,
        privateKey: String,
        result: Map<String, String>
    ): String {
        fun required(name: String): String = result[name]?.takeIf { it.isNotBlank() }
            ?: error("Server did not return $name")

        val endpointHost = if (host.contains(':')) "[$host]" else host
        return """
            # Self-hosted AmneziaWG ($host)
            [Interface]
            Address = ${required("client_ip")}/32
            DNS = 1.1.1.1, 1.0.0.1
            MTU = 1280
            PrivateKey = $privateKey
            Jc = ${required("jc")}
            Jmin = ${required("jmin")}
            Jmax = ${required("jmax")}
            S1 = ${required("s1")}
            S2 = ${required("s2")}
            H1 = ${required("h1")}
            H2 = ${required("h2")}
            H3 = ${required("h3")}
            H4 = ${required("h4")}

            [Peer]
            PublicKey = ${required("server_public")}
            PresharedKey = ${required("psk")}
            AllowedIPs = 0.0.0.0/0, ::/0
            Endpoint = $endpointHost:${required("port")}
            PersistentKeepalive = 25
        """.trimIndent()
    }

    private data class WireGuardKeyPair(val privateKey: String, val publicKey: String)

    private class TailOutputStream(private val capacity: Int) : OutputStream() {
        private val buffer = ByteArray(capacity)
        private var size = 0
        private var next = 0

        @Synchronized
        override fun write(value: Int) {
            buffer[next] = value.toByte()
            next = (next + 1) % capacity
            if (size < capacity) size++
        }

        @Synchronized
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
            for (index in offset until offset + length) write(bytes[index].toInt())
        }

        @Synchronized
        fun text(): String {
            val bytes = if (size < capacity) {
                buffer.copyOf(size)
            } else {
                buffer.copyOfRange(next, capacity) + buffer.copyOfRange(0, next)
            }
            return bytes.toString(Charsets.UTF_8)
        }
    }

    private companion object {
        const val DEFAULT_SSH_PORT = 22
        const val CONNECT_TIMEOUT_MS = 15_000
        const val SERVER_ALIVE_INTERVAL_MS = 10_000
        const val SERVER_ALIVE_COUNT = 3
        const val CHANNEL_POLL_MS = 100L
        const val MAX_ERROR_LENGTH = 2_000
        const val STAGE_PREFIX = "OLCBOX_STAGE "
        const val RESULT_PREFIX = "OLCBOX_RESULT "
        const val ERROR_PREFIX = "OLCBOX_ERROR "
        const val CLIENT_PUBLIC_KEY_PLACEHOLDER = "__OLCBOX_CLIENT_PUBLIC_KEY__"
        const val SUDO_PASSWORD_PLACEHOLDER = "__OLCBOX_SUDO_PASSWORD__"

        val PROVISION_SCRIPT = """
            #!/bin/sh
            set -eu

            stage() { printf 'OLCBOX_STAGE %s\n' "${'$'}1"; }
            fail() { printf 'OLCBOX_ERROR %s\n' "${'$'}1"; exit 1; }

            CLIENT_PUBLIC_KEY='$CLIENT_PUBLIC_KEY_PLACEHOLDER'
            SUDO_PASSWORD='$SUDO_PASSWORD_PLACEHOLDER'
            CONTAINER_NAME='olcbox-amnezia-awg'
            IMAGE='amneziavpn/amnezia-wg:latest'
            DATA_DIR='/opt/olcbox/amnezia-awg'
            CONFIG="${'$'}DATA_DIR/wg0.conf"
            SERVER_PRIVATE_FILE="${'$'}DATA_DIR/server-private.key"
            SERVER_PUBLIC_FILE="${'$'}DATA_DIR/server-public.key"
            PSK_FILE="${'$'}DATA_DIR/preshared.key"
            PORT='55424'

            if [ "${'$'}(id -u)" -eq 0 ]; then
              SUDO=''
            else
              command -v sudo >/dev/null 2>&1 || fail 'The SSH user is not root and sudo is unavailable'
              if ! sudo -n true >/dev/null 2>&1; then
                printf '%s\n' "${'$'}SUDO_PASSWORD" | sudo -S -p '' -v >/dev/null 2>&1 ||
                  fail 'The SSH user does not have sudo access'
              fi
              SUDO='sudo -n'
            fi
            SUDO_PASSWORD=''

            docker_cmd() { ${'$'}SUDO docker "${'$'}@"; }

            stage 'Checking Docker'
            if ! command -v docker >/dev/null 2>&1; then
              stage 'Installing Docker'
              if command -v apt-get >/dev/null 2>&1; then
                ${'$'}SUDO apt-get -yq update
                ${'$'}SUDO apt-get -yq install --install-recommends docker.io
              elif command -v dnf >/dev/null 2>&1; then
                ${'$'}SUDO dnf -yq install docker
              elif command -v yum >/dev/null 2>&1; then
                ${'$'}SUDO yum -y -q install docker
              elif command -v zypper >/dev/null 2>&1; then
                ${'$'}SUDO zypper -nq install docker
              elif command -v pacman >/dev/null 2>&1; then
                ${'$'}SUDO pacman -S --noconfirm --noprogressbar docker
              else
                fail 'Unsupported Linux distribution: install Docker first'
              fi
            fi

            ${'$'}SUDO systemctl enable --now docker >/dev/null 2>&1 ||
              ${'$'}SUDO service docker start >/dev/null 2>&1 || true
            docker_cmd version >/dev/null 2>&1 || fail 'Docker daemon is not available'

            stage 'Downloading AmneziaWG image'
            docker_cmd pull "${'$'}IMAGE" >/dev/null
            ${'$'}SUDO mkdir -p "${'$'}DATA_DIR"
            ${'$'}SUDO chmod 700 "${'$'}DATA_DIR"

            rand_range() {
              min="${'$'}1"; max="${'$'}2"
              value="${'$'}(od -An -N4 -tu4 /dev/urandom | tr -d ' ')"
              printf '%s\n' "${'$'}((min + value % (max - min + 1)))"
            }

            if [ ! -s "${'$'}CONFIG" ]; then
              stage 'Generating server keys'
              SERVER_PRIVATE="${'$'}(docker_cmd run --rm --entrypoint sh "${'$'}IMAGE" -c 'wg genkey')"
              SERVER_PUBLIC="${'$'}(printf '%s\n' "${'$'}SERVER_PRIVATE" | docker_cmd run -i --rm --entrypoint sh "${'$'}IMAGE" -c 'wg pubkey')"
              PSK="${'$'}(docker_cmd run --rm --entrypoint sh "${'$'}IMAGE" -c 'wg genpsk')"
              JC="${'$'}(rand_range 4 6)"
              JMIN='10'
              JMAX='50'
              S1="${'$'}(rand_range 12 149)"
              S2="${'$'}(rand_range 12 149)"
              while [ "${'$'}S1" -eq "${'$'}S2" ] || [ "${'$'}((S1 + 148))" -eq "${'$'}((S2 + 92))" ]; do
                S2="${'$'}(rand_range 12 149)"
              done

              printf '%s\n' "${'$'}SERVER_PRIVATE" | ${'$'}SUDO tee "${'$'}SERVER_PRIVATE_FILE" >/dev/null
              printf '%s\n' "${'$'}SERVER_PUBLIC" | ${'$'}SUDO tee "${'$'}SERVER_PUBLIC_FILE" >/dev/null
              printf '%s\n' "${'$'}PSK" | ${'$'}SUDO tee "${'$'}PSK_FILE" >/dev/null
              ${'$'}SUDO chmod 600 "${'$'}SERVER_PRIVATE_FILE" "${'$'}SERVER_PUBLIC_FILE" "${'$'}PSK_FILE"

              ${'$'}SUDO tee "${'$'}CONFIG" >/dev/null <<EOF
            [Interface]
            PrivateKey = ${'$'}SERVER_PRIVATE
            Address = 10.8.1.1/24
            ListenPort = ${'$'}PORT
            Jc = ${'$'}JC
            Jmin = ${'$'}JMIN
            Jmax = ${'$'}JMAX
            S1 = ${'$'}S1
            S2 = ${'$'}S2
            H1 = 1
            H2 = 2
            H3 = 3
            H4 = 4
            EOF
              ${'$'}SUDO chmod 600 "${'$'}CONFIG"
            fi

            read_value() { ${'$'}SUDO awk -F= -v key="${'$'}1" '${'$'}1 ~ "^[[:space:]]*" key "[[:space:]]*${'$'}" { gsub(/^[[:space:]]+|[[:space:]]+${'$'}/, "", ${'$'}2); print ${'$'}2; exit }' "${'$'}CONFIG"; }
            SERVER_PUBLIC="${'$'}(${ '$' }SUDO cat "${'$'}SERVER_PUBLIC_FILE")"
            PSK="${'$'}(${ '$' }SUDO cat "${'$'}PSK_FILE")"
            JC="${'$'}(read_value Jc)"; JMIN="${'$'}(read_value Jmin)"; JMAX="${'$'}(read_value Jmax)"
            S1="${'$'}(read_value S1)"; S2="${'$'}(read_value S2)"
            H1="${'$'}(read_value H1)"; H2="${'$'}(read_value H2)"; H3="${'$'}(read_value H3)"; H4="${'$'}(read_value H4)"

            PEER_COUNT="${'$'}(${ '$' }SUDO grep -c '^\[Peer\]' "${'$'}CONFIG" 2>/dev/null || true)"
            CLIENT_OCTET="${'$'}((PEER_COUNT + 2))"
            [ "${'$'}CLIENT_OCTET" -le 254 ] || fail 'The AmneziaWG subnet has no free client addresses'
            CLIENT_IP="10.8.1.${'$'}CLIENT_OCTET"

            stage 'Adding VPN client'
            ${'$'}SUDO tee -a "${'$'}CONFIG" >/dev/null <<EOF

            [Peer]
            PublicKey = ${'$'}CLIENT_PUBLIC_KEY
            PresharedKey = ${'$'}PSK
            AllowedIPs = ${'$'}CLIENT_IP/32
            EOF

            ${'$'}SUDO tee "${'$'}DATA_DIR/start.sh" >/dev/null <<'EOF'
            #!/bin/sh
            set -e
            WAN_INTERFACE="${'$'}(ip -4 route show default | awk '{ print ${'$'}5; exit }')"
            [ -n "${'$'}WAN_INTERFACE" ] || WAN_INTERFACE='eth0'
            wg-quick down /opt/amnezia/awg/wg0.conf >/dev/null 2>&1 || true
            wg-quick up /opt/amnezia/awg/wg0.conf
            iptables -C INPUT -i wg0 -j ACCEPT 2>/dev/null || iptables -A INPUT -i wg0 -j ACCEPT
            iptables -C FORWARD -i wg0 -j ACCEPT 2>/dev/null || iptables -A FORWARD -i wg0 -j ACCEPT
            iptables -C OUTPUT -o wg0 -j ACCEPT 2>/dev/null || iptables -A OUTPUT -o wg0 -j ACCEPT
            iptables -C FORWARD -m state --state ESTABLISHED,RELATED -j ACCEPT 2>/dev/null || iptables -A FORWARD -m state --state ESTABLISHED,RELATED -j ACCEPT
            iptables -t nat -C POSTROUTING -s 10.8.1.0/24 -o "${'$'}WAN_INTERFACE" -j MASQUERADE 2>/dev/null || iptables -t nat -A POSTROUTING -s 10.8.1.0/24 -o "${'$'}WAN_INTERFACE" -j MASQUERADE
            exec tail -f /dev/null
            EOF
            ${'$'}SUDO chmod 700 "${'$'}DATA_DIR/start.sh"

            stage 'Starting AmneziaWG'
            docker_cmd rm -f "${'$'}CONTAINER_NAME" >/dev/null 2>&1 || true
            docker_cmd run -d \
              --log-driver none \
              --restart always \
              --privileged \
              --cap-add=NET_ADMIN \
              --cap-add=SYS_MODULE \
              -p "${'$'}PORT:${'$'}PORT/udp" \
              -v /lib/modules:/lib/modules \
              -v "${'$'}DATA_DIR:/opt/amnezia/awg" \
              --sysctl='net.ipv4.conf.all.src_valid_mark=1' \
              --name "${'$'}CONTAINER_NAME" \
              --entrypoint /bin/sh \
              "${'$'}IMAGE" /opt/amnezia/awg/start.sh >/dev/null

            sleep 2
            docker_cmd exec "${'$'}CONTAINER_NAME" wg show wg0 >/dev/null 2>&1 || fail 'AmneziaWG did not start'

            printf 'OLCBOX_RESULT server_public=%s\n' "${'$'}SERVER_PUBLIC"
            printf 'OLCBOX_RESULT psk=%s\n' "${'$'}PSK"
            printf 'OLCBOX_RESULT client_ip=%s\n' "${'$'}CLIENT_IP"
            printf 'OLCBOX_RESULT port=%s\n' "${'$'}PORT"
            printf 'OLCBOX_RESULT jc=%s\n' "${'$'}JC"
            printf 'OLCBOX_RESULT jmin=%s\n' "${'$'}JMIN"
            printf 'OLCBOX_RESULT jmax=%s\n' "${'$'}JMAX"
            printf 'OLCBOX_RESULT s1=%s\n' "${'$'}S1"
            printf 'OLCBOX_RESULT s2=%s\n' "${'$'}S2"
            printf 'OLCBOX_RESULT h1=%s\n' "${'$'}H1"
            printf 'OLCBOX_RESULT h2=%s\n' "${'$'}H2"
            printf 'OLCBOX_RESULT h3=%s\n' "${'$'}H3"
            printf 'OLCBOX_RESULT h4=%s\n' "${'$'}H4"
            stage 'Server is ready'
        """.trimIndent() + "\n"
    }
}
