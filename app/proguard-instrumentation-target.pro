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
# Compose/Espresso is packaged in the test APK and calls shared app dependencies.
# These rules are used only with -PdeviceTestBuildType=release, never for distribution.
-keep class androidx.compose.** { *; }
-keep class androidx.collection.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class com.photoanswer.app.AnswerContentKt {
    public static io.noties.markwon.Markwon answerRenderer(android.content.Context, float);
}
-keep class com.photoanswer.app.TaskDatabase { *; }
-keep class com.photoanswer.app.TaskStore { *; }
-keep class com.photoanswer.app.SecurePrefs { *; }
-keep class com.photoanswer.app.SolveTask { *; }
-keep class com.photoanswer.app.MathMarkdown { *; }
