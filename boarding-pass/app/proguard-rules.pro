# kotlinx.serialization ships its own rules; keep our models' serializers explicitly as well.
-keepclassmembers @kotlinx.serialization.Serializable class io.github.cosmicoverlord.boardingpass.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class io.github.cosmicoverlord.boardingpass.**$$serializer { *; }

# WorkManager instantiates workers reflectively.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
