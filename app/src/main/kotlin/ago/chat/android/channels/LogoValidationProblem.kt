package ago.chat.android.channels

import android.graphics.BitmapFactory

/**
 * `26-191`/`C4` (`docs/design/tenant-channels-android.md` §3.4): the client-side courtesy check the
 * picked logo runs through *before* [BrandingViewModel.uploadLogo] is ever called - mirrors the
 * console's own `emailChannelLogoValidation.ts`, field for field: an allowed MIME type, a size window,
 * and a decoded-dimensions ceiling.
 *
 * **Never the authority.** The real, authoritative validation (true format, real dimensions,
 * not-animated) is `Ago.Chat.Worker.SiteLogoValidator` (`adr/0177`), run asynchronously after upload -
 * an accepted-here file can still come back [ago.chat.android.core.domain.branding.LogoStatus.Rejected]
 * (an animated GIF, most likely). This function only stops an obviously-wrong file before a round trip;
 * it never claims success - only [ago.chat.android.core.domain.branding.LogoStatus] does.
 *
 * **Why this lives in `:app`, not `:core:domain` or `:core:network`.** [BitmapFactory] is Android
 * framework (`android.graphics`), so a function that calls it cannot sit in either `:core:*` module
 * without giving a plain-Kotlin/plain-Ktor module an Android dependency it has no other reason to carry
 * - the identical dependency-rule boundary
 * [ago.chat.android.core.domain.branding.SiteBrandingApi]'s own doc comment draws for reading a
 * `content://` `Uri`'s bytes and MIME type in the first place.
 *
 * @param bytes the picked file's own bytes, already read off its `content://` `Uri`.
 * @param contentType the MIME type `ContentResolver.getType` reported for that `Uri`.
 */
internal fun validateLogoCourtesy(
    bytes: ByteArray,
    contentType: String,
): LogoValidationProblem? {
    if (contentType !in ALLOWED_LOGO_CONTENT_TYPES) return LogoValidationProblem.InvalidFormat
    if (bytes.isEmpty() || bytes.size > MAX_LOGO_BYTES) return LogoValidationProblem.TooLarge

    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return LogoValidationProblem.Undecodable
    if (options.outWidth > MAX_LOGO_DIMENSION_PX || options.outHeight > MAX_LOGO_DIMENSION_PX) {
        return LogoValidationProblem.InvalidDimensions
    }

    return null
}

/** `docs/design/tenant-channels-android.md` §3.4's own four reasons, each its own string
 * (`ago.chat.android.channels.logoValidationProblemTextRes` in `BrandingScreen.kt`) - never a shared
 * "invalid file" catch-all. */
internal enum class LogoValidationProblem {
    InvalidFormat,
    TooLarge,
    InvalidDimensions,
    Undecodable,
}

private val ALLOWED_LOGO_CONTENT_TYPES = setOf("image/png", "image/jpeg", "image/gif")

/** 200 KiB, `emailChannelLogoValidation.ts`'s own ceiling. */
private const val MAX_LOGO_BYTES: Int = 200 * 1024

/** 100x100px, `emailChannelLogoValidation.ts`'s own ceiling. */
private const val MAX_LOGO_DIMENSION_PX: Int = 100
