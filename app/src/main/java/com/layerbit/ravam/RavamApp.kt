package com.layerbit.ravam

import android.app.Application
import android.os.Build
import org.conscrypt.Conscrypt
import java.security.Security

class RavamApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // The embedded-ADB pairing and connection use TLS 1.3 with channel-binding
        // keying material that the platform SSL engine does not expose. Conscrypt does,
        // so install it as the top security provider. Needed for Ravam's own shell path.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
            }
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }
    }
}
