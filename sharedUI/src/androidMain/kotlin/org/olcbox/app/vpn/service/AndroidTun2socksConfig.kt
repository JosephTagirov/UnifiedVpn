package org.olcbox.app.vpn.service

internal fun buildAndroidTun2socksConfig(
    mtu: Int,
    ipv4Address: String,
    socksHost: String,
    socksPort: Int,
    username: String,
    password: String
): String = """
    tunnel:
      name: tun0
      mtu: $mtu
      multi-queue: false
      ipv4: $ipv4Address

    socks5:
      address: $socksHost
      port: $socksPort
      # Standard SOCKS5 UDP ASSOCIATE is supported by sing-box and Xray.
      # The tcp mode uses hev's private command 5, which these cores reject.
      udp: 'udp'
      pipeline: false
      username: '$username'
      password: '$password'

    misc:
      task-stack-size: 24576
      # The download ring retains bytes until TCP ACK; keep the upstream capacity.
      tcp-buffer-size: 65536
      max-session-count: 1200
      connect-timeout: 10000
      tcp-read-write-timeout: 300000
      udp-read-write-timeout: 60000
      log-file: stderr
      log-level: warn
""".trimIndent()
