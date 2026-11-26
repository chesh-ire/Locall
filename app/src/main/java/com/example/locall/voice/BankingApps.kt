package com.example.locall.voice

/**
 * Apps that refuse to run while an accessibility service with gesture/screen-read
 * capability is enabled (their anti-fraud RASP SDKs — Promon, Zimperium, Appdome,
 * Play Integrity — treat it as an accessibility-trojan signal and lock themselves).
 *
 * When one of these comes to the foreground, [VoiceAccessibilityService] pauses itself
 * via disableSelf() so the bank works, and posts a notification to re-enable later.
 *
 * Matching is: exact [KNOWN] package first, then a conservative keyword fallback so
 * banks we didn't enumerate still trip it. The [KNOWN] set is the primary lever —
 * add a package here if a specific app isn't being caught.
 */
object BankingApps {

    // Known banking / UPI / wallet packages (India-first, plus a few global).
    private val KNOWN = setOf(
        // UPI apps & wallets
        "com.phonepe.app",
        "net.one97.paytm",
        "com.google.android.apps.nbu.paisa.user", // Google Pay (India)
        "in.org.npci.upiapp",                     // BHIM
        "com.dreamplug.androidapp",               // CRED
        "com.mobikwik_new",
        "com.freecharge.android",
        "com.whatsapp.w4b",                       // (business — UPI payments)
        // Major Indian bank apps
        "com.sbi.lotusintouch",                   // SBI YONO
        "com.sbi.SBIFreedomPlus",                 // SBI Anywhere
        "com.csam.icici.bank.imobile",            // ICICI iMobile Pay
        "com.snapwork.hdfc",                      // HDFC Bank
        "com.axis.mobile",                        // Axis Mobile
        "com.msf.kbank.mobile",                   // Kotak 811
        "com.bankofbaroda.mconnect",              // BoB World
        "com.fss.pnbpsp",                         // PNB ONE
        "com.YES.YesBank",                        // YES Bank
        "com.fss.idfcpsp",                        // IDFC FIRST
        "com.infrasoft.uboi",                     // Union Bank
        // Global
        "com.chase.sig.android",                  // Chase
        "com.konylabs.capitalone",                // Capital One
        "com.infonow.bofa",                       // Bank of America
        "com.wf.wellsfargomobile",                // Wells Fargo
    ).map { it.lowercase() }.toSet()

    // Strong tokens that almost only appear in banking/payment package names.
    private val TOKENS = listOf(
        "bank", "upi", "paytm", "phonepe", "npci", "paisa",
        "mobikwik", "freecharge", "bhim", "payments",
    )

    fun isBankingApp(packageName: CharSequence?): Boolean {
        val pkg = packageName?.toString()?.lowercase() ?: return false
        if (pkg.isBlank()) return false
        if (pkg in KNOWN) return true
        return TOKENS.any { pkg.contains(it) }
    }
}
