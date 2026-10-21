# AndroidX test uses these compile-time annotations; they have no device runtime behavior.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed
# This type is used only as a value in a compile-time Error Prone annotation.
-dontwarn javax.lang.model.element.Modifier
