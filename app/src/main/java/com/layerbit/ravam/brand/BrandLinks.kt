package com.layerbit.ravam.brand

/**
 * Company-wide destinations, shared by every app in the Layerbit family.
 *
 * Kept in one object so a new app picks up the same links for free, and so a change of
 * support address is one edit rather than a hunt. These mirror the values LayerLink
 * uses — they belong to the company, not to this app.
 */
object BrandLinks {
    const val WEBSITE_URL = "https://layerbit.co.in"
    const val COFFEE_URL = "https://www.buymeacoffee.com/layerbit"
    const val WHATSAPP_URL = "https://wa.me/916282595823"
    const val SUPPORT_EMAIL = "ceo@layerbit.co.in"

    /** Pre-filled so a support mail arrives already labelled with the app it came from. */
    const val SUPPORT_MAILTO = "mailto:$SUPPORT_EMAIL?subject=Ravam%20Support"
}
