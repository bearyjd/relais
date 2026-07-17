/*
 * Copyright (C) 2026 Entrevoix / grepon.cc
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Affero General Public License for more details.
 */

package cc.grepon.relais

import android.content.Context
import android.util.Log
import java.io.File
import java.math.BigInteger
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Self-signed LAN TLS for the node's HTTPS listener (extracted from `RelaisHttpServer` — #173, the
 * server class shouldn't also own keystore/cert minting). The cert is a self-signed LAN cert (clients
 * use `curl -k`), generated once at first use into an app-private PKCS12 protected by a random
 * per-install password ([RelaisConfig.tlsKeystorePassword]). No key material or password is bundled
 * in the APK or committed to the repo.
 */
internal object RelaisTls {
  private const val TAG = "RelaisTls"
  private const val TLS_KEY_ALIAS = "relais-tls"
  private const val TLS_KEYSTORE_FILE = "relais_tls.p12"

  /**
   * Plain (tls=false) or TLS server socket.
   *
   * A **software** RSA key is used deliberately: AndroidKeyStore keys (RSA and EC) cannot sign the
   * TLS server handshake through conscrypt's native upcall on-device, so the key is generated in
   * software and stored in the app's private files dir.
   */
  fun buildServerSocket(context: Context, tls: Boolean): ServerSocket {
    if (!tls) return ServerSocket()
    val pass = RelaisConfig.tlsKeystorePassword(context).toCharArray()
    val ks = loadOrCreateKeystore(context, pass)
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, pass) }
    val ctx = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
    return ctx.serverSocketFactory.createServerSocket()
  }

  /** Loads the app-private TLS keystore, generating a fresh self-signed cert on first use. */
  private fun loadOrCreateKeystore(context: Context, pass: CharArray): KeyStore {
    val file = File(context.filesDir, TLS_KEYSTORE_FILE)
    val ks = KeyStore.getInstance("PKCS12")
    if (file.exists()) {
      file.inputStream().use { ks.load(it, pass) }
      return ks
    }
    ks.load(null, pass)
    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    ks.setKeyEntry(TLS_KEY_ALIAS, keyPair.private, pass, arrayOf(selfSignedCert(keyPair)))
    file.outputStream().use { ks.store(it, pass) }
    Log.i(TAG, "Generated self-signed TLS keystore at ${file.path}")
    return ks
  }

  /** Builds a 30-year self-signed `CN=relais-node` X509 cert for [keyPair] (SHA256withRSA). */
  private fun selfSignedCert(keyPair: KeyPair): X509Certificate {
    val now = System.currentTimeMillis()
    val notBefore = Date(now - 60_000) // small backdate for client clock skew
    val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 30) }.time
    val name = X500Name("CN=relais-node")
    val builder =
      JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), notBefore, notAfter, name, keyPair.public)
    val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
    return JcaX509CertificateConverter().getCertificate(builder.build(signer))
  }
}
