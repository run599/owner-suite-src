package com.guard.activator

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.util.Date

/**
 * ADB 身份：软件生成的 RSA 密钥 + 自签 X.509 证书。
 *
 * 为什么不用 AndroidKeyStore：ADB 认证需要对 token 做 RSA/ECB/NoPadding 裸签，
 * 而 AndroidKeyStore 禁止 NoPadding（IND-CPA 要求），会抛
 * InvalidAlgorithmParameterException。所以必须在软件层生成 keypair。
 */
object Identity {

    private const val FILE = "adb_identity.p12"
    private val PASS = "owner-activator".toCharArray()

    @Volatile
    private var cached: Pair<PrivateKey, Certificate>? = null

    @Synchronized
    fun get(ctx: Context): Pair<PrivateKey, Certificate> {
        cached?.let { return it }
        val f = File(ctx.filesDir, FILE)

        val ks = KeyStore.getInstance("PKCS12")
        if (f.exists() && f.length() > 0) {
            f.inputStream().use { ks.load(it, PASS) }
            val priv = ks.getKey("adb", null) as PrivateKey
            val cert = ks.getCertificate("adb")
            return (priv to cert).also { cached = it }
        }

        val (priv, cert) = generate()
        ks.load(null, PASS)
        ks.setKeyEntry("adb", priv, null, arrayOf(cert))
        f.outputStream().use { ks.store(it, PASS) }

        return (priv to cert).also { cached = it }
    }

    private fun generate(): Pair<PrivateKey, Certificate> {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + 10L * 365 * 24 * 3600 * 1000)
        val subject = X500Name("CN=owner-activator, O=local")
        val serial = BigInteger.valueOf(now)

        val builder = JcaX509v3CertificateBuilder(
            subject, serial, notBefore, notAfter, subject, kp.public
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(kp.private)
        val cert: X509Certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        cert.checkValidity(Date())
        return kp.private to cert
    }
}
