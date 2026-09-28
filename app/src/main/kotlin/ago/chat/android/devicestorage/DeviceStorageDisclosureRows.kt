package ago.chat.android.devicestorage

import ago.chat.android.R
import androidx.annotation.StringRes

/**
 * `26-256`: one row of the tenant-facing device-storage disclosure — one key the widget writes to a
 * visitor's own `localStorage`, and the three facts a tenant needs to declare it: what it holds, why it
 * exists, and how long it lives. Mirrors `ago-console`'s own `DeviceStorageDisclosureRow`.
 *
 * [key] is the technical key suffix a tenant sees in their browser's dev tools, after
 * `ago-chat:<site key>:` — an identical literal in every locale (it is the code's own key name, not prose),
 * so it is data here rather than a string resource, exactly as the console keeps it a literal string in its
 * own `DEVICE_STORAGE_DISCLOSURE_ROWS`. `last-sequence:<conversationId>` is templated — one physical key per
 * conversation the browser has resumed — stated as such rather than picking one example id.
 * [holdsRes]/[whyRes]/[lifetimeRes] name the real, translatable prose in `strings.xml`.
 */
internal data class DeviceStorageDisclosureRow(
    val key: String,
    @param:StringRes val holdsRes: Int,
    @param:StringRes val whyRes: Int,
    @param:StringRes val lifetimeRes: Int,
)

/**
 * **Not a live import.** `ago-widget`, `ago-console` and `ago-android` are separate repositories that build
 * and deploy independently — this app does not consume the widget or console as a package, so there is no
 * mechanical link between this list and `ago-widget/src/storage.ts`'s own `WIDGET_STORAGE_DISCLOSURE`, the
 * single source of truth for what the widget actually writes. This array is a **hand-maintained copy** of
 * `ago-console`'s own `DEVICE_STORAGE_DISCLOSURE_ROWS` (itself a hand-maintained copy of the widget's list),
 * in the same order. Closing that gap for real would need a published package or generated artifact none of
 * the three repositories has today — named here rather than quietly assumed solved, the same posture the
 * console file's own doc comment takes.
 */
internal val DEVICE_STORAGE_DISCLOSURE_ROWS: List<DeviceStorageDisclosureRow> =
    listOf(
        DeviceStorageDisclosureRow(
            key = "visitor-token",
            holdsRes = R.string.device_storage_visitor_token_holds,
            whyRes = R.string.device_storage_visitor_token_why,
            lifetimeRes = R.string.device_storage_visitor_token_lifetime,
        ),
        DeviceStorageDisclosureRow(
            key = "visitor-id",
            holdsRes = R.string.device_storage_visitor_id_holds,
            whyRes = R.string.device_storage_visitor_id_why,
            lifetimeRes = R.string.device_storage_visitor_id_lifetime,
        ),
        DeviceStorageDisclosureRow(
            key = "widget-color",
            holdsRes = R.string.device_storage_widget_color_holds,
            whyRes = R.string.device_storage_widget_color_why,
            lifetimeRes = R.string.device_storage_widget_color_lifetime,
        ),
        DeviceStorageDisclosureRow(
            key = "widget-position",
            holdsRes = R.string.device_storage_widget_position_holds,
            whyRes = R.string.device_storage_widget_position_why,
            lifetimeRes = R.string.device_storage_widget_position_lifetime,
        ),
        DeviceStorageDisclosureRow(
            key = "widget-locale",
            holdsRes = R.string.device_storage_widget_locale_holds,
            whyRes = R.string.device_storage_widget_locale_why,
            lifetimeRes = R.string.device_storage_widget_locale_lifetime,
        ),
        DeviceStorageDisclosureRow(
            key = "widget-notice-text",
            holdsRes = R.string.device_storage_widget_notice_text_holds,
            whyRes = R.string.device_storage_widget_notice_text_why,
            lifetimeRes = R.string.device_storage_widget_notice_text_lifetime,
        ),
        DeviceStorageDisclosureRow(
            key = "widget-notice-url",
            holdsRes = R.string.device_storage_widget_notice_url_holds,
            whyRes = R.string.device_storage_widget_notice_url_why,
            lifetimeRes = R.string.device_storage_widget_notice_url_lifetime,
        ),
        DeviceStorageDisclosureRow(
            key = "enabled-modules",
            holdsRes = R.string.device_storage_enabled_modules_holds,
            whyRes = R.string.device_storage_enabled_modules_why,
            lifetimeRes = R.string.device_storage_enabled_modules_lifetime,
        ),
        DeviceStorageDisclosureRow(
            key = "conversation-id",
            holdsRes = R.string.device_storage_conversation_id_holds,
            whyRes = R.string.device_storage_conversation_id_why,
            lifetimeRes = R.string.device_storage_conversation_id_lifetime,
        ),
        DeviceStorageDisclosureRow(
            key = "last-sequence:<conversationId>",
            holdsRes = R.string.device_storage_last_sequence_holds,
            whyRes = R.string.device_storage_last_sequence_why,
            lifetimeRes = R.string.device_storage_last_sequence_lifetime,
        ),
    )
