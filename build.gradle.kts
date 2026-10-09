// Intentionally empty.
//
// Each module declares the plugins it needs. Declaring them here, even with
// `apply false`, makes Gradle resolve the Android plugin marker before it knows whether
// anyone wants it — which fails on any machine without access to Google's Maven, and
// would stop :core being tested there. :core must be buildable anywhere.
//
// This costs one build warning: because :core applies kotlin.jvm and :app applies
// kotlin.android, Gradle loads the Kotlin plugin into two subproject classloaders and
// says that "is not supported and may break the build". The usual cure — naming the
// plugins here with `apply false` — is not available. Hoisting kotlin.android to the
// root classloader puts it somewhere AGP is not, and applying it then dies on
// `NoClassDefFoundError: com/android/build/gradle/api/BaseVariant`. Curing the warning
// means hoisting AGP too, which is the thing this file exists to avoid. The warning is
// the price of :core building on a machine with no Android SDK; it is the better trade.
