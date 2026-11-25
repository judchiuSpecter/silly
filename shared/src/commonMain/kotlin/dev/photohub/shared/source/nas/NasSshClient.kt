package dev.photohub.shared.source.nas

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.SecurityUtils

/**
 * SSH client configured for Android's JCE providers.
 *
 * sshj registers a crypto provider by *name* ("BC"). On desktop that resolves
 * to its bundled BouncyCastle, but Android already ships an older provider
 * called "BC", so sshj adopts that one — and it lacks both X25519 (curve25519
 * key exchange fails) and the `SHA-256` MessageDigest alias (SSH digest/MACs
 * fail). Turning off sshj's BC registration makes it use the platform JCE
 * providers, which implement both. The classic DH exchanges are kept as well,
 * since they are what this QNAP's OpenSSH 7.6 negotiates reliably.
 */
internal fun newNasSshClient(): SSHClient {
    SecurityUtils.setRegisterBouncyCastle(false)
    SecurityUtils.setSecurityProvider(null)
    val config = DefaultConfig()
    config.keyExchangeFactories = config.keyExchangeFactories
        .filter { it.name.startsWith("diffie-hellman") }
    return SSHClient(config)
}
