package ago.chat.android.billing

/**
 * `26-293`: the native YooKassa SDK's own two client-side identifiers - `clientApplicationKey` lets the
 * SDK tokenize a card/SBP/SberPay payment; `shopId` names the merchant. Neither can move money on its own
 * (`docs/backlog/26-289-*.md` §3 - the one-time token it produces is worthless until the backend redeems
 * it with the server-side Secret Key inside `POST /payments`), which is exactly why this is client
 * *config*, not a server secret: it ships inside every APK by design, the same "who holds and presents the
 * credential decides where it lives" reasoning `OidcConfig`'s own doc comment already states for the
 * public OIDC client id.
 *
 * Both fields are nullable — **not** a hardcoded literal anywhere in this committed repository
 * (`docs/backlog/26-289-*.md` §3's own instruction, and CLAUDE.md's "everything is public" rule, apply
 * even to a test-mode value): [ago.chat.android.di.AppModule.provideYooKassaConfig] reads them from
 * `BuildConfig`, which `app/build.gradle.kts` fills from `local.properties`/a `-P` Gradle property with no
 * committed default, the identical `String?`-when-absent shape [ago.chat.android.session.OidcConfig.calendarApiBaseUrl]
 * already establishes for a deployment that genuinely does not have one configured. A build with neither
 * value present still compiles and runs; Card A's «Докупить» buttons simply cannot start a payment
 * ([BillingViewModel] never calls the SDK without both).
 */
public data class YooKassaConfig(
    val clientApplicationKey: String?,
    val shopId: String?,
) {
    /** Whether this build can take a native payment at all - both fields are needed together, since a
     * `PaymentParameters` with one and not the other is not a shape the SDK accepts. */
    public val isConfigured: Boolean get() = clientApplicationKey != null && shopId != null
}
