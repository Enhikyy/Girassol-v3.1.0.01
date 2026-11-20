package com.enhikyy.girassol.system

import android.content.Context
import android.content.pm.PackageManager
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

enum class SigningKind(val label: String) {
    DEBUG("chave de debug"),
    RELEASE("keystore de release"),
    UNKNOWN("desconhecida")
}

data class SigningReport(
    val kind: SigningKind,
    val subject: String,
    val sha256: String,
    val schemeRotation: Boolean,
    val signers: Int
)

/**
 * Identifica se o APK instalado foi assinado com a chave de debug do Android SDK
 * (CN=Android Debug, O=Android, C=US) ou com a keystore definitiva de release.
 * Trocar de assinatura exige desinstalar o app — e isso apaga os dados internos;
 * por isso a aba Sistema lembra de exportar o snapshot .grs antes.
 */
object SigningInfo {
    const val DEBUG_DN = "CN=Android Debug"

    fun classify(subjectDn: String): SigningKind = when {
        subjectDn.isBlank() -> SigningKind.UNKNOWN
        subjectDn.contains(DEBUG_DN, ignoreCase = true) -> SigningKind.DEBUG
        else -> SigningKind.RELEASE
    }

    fun fingerprint(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") { "%02X".format(it) }

    fun read(context: Context): SigningReport = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        val si = info.signingInfo ?: return@runCatching SigningReport(SigningKind.UNKNOWN, "", "", false, 0)
        val sigs = if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory
        val first = sigs?.lastOrNull() ?: return@runCatching SigningReport(SigningKind.UNKNOWN, "", "", false, 0)
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(first.toByteArray())) as X509Certificate
        val dn = cert.subjectX500Principal.name
        SigningReport(classify(dn), dn, fingerprint(first.toByteArray()), (si.signingCertificateHistory?.size ?: 0) > 1, sigs.size)
    }.getOrElse { SigningReport(SigningKind.UNKNOWN, it.message ?: "", "", false, 0) }
}
