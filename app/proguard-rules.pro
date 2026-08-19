# kotlinx.serialization ships its own consumer rules, these are belt-and-braces
# for the @Serializable models this app persists and syncs.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keep,includedescriptorclasses class com.henny.checklist.data.**$$serializer { *; }
-keepclassmembers class com.henny.checklist.data.** {
    *** Companion;
}
-keepclasseswithmembers class com.henny.checklist.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Broadcast receivers referenced only from the manifest
-keep class com.henny.checklist.notify.** { *; }
