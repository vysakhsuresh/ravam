// Intentionally empty.
//
// Each module declares the plugins it needs. Declaring them here, even with
// `apply false`, makes Gradle resolve the Android plugin marker before it knows whether
// anyone wants it — which fails on any machine without access to Google's Maven, and
// would stop :core being tested there. :core must be buildable anywhere.
