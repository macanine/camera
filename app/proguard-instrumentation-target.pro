# The test runner calls this app-provided dependency from outside the application DEX.
# Keep it in instrumented release builds so test/app dependency deduplication is valid.
-keep class androidx.tracing.** { *; }
# Instrumentation dependencies share the target's runtime classes, including facades
# normally inlined away by R8. These rules apply only when testing the release variant.
-keep class kotlin.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.lifecycle.** { *; }
-keep class androidx.activity.** { *; }
-keep class androidx.concurrent.futures.** { *; }
