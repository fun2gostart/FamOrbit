# FamOrbit R8 / ProGuard Configuration for High DEX Optimization & Shrinking

# Enable code optimizations
-repackageclasses 'com.familycontrol.lab.o'
-allowaccessmodification

# Kotlin & Coroutines
-dontwarn kotlinx.coroutines.**

# AndroidX & WorkManager
-keep class androidx.work.Worker { *; }
-keep class androidx.work.ListenableWorker { *; }
-keep class androidx.work.WorkerParameters { *; }
-keep class * extends androidx.work.Worker { *; }

# Keep data models used in JSON serialization / deserialization
-keepclassmembers class com.familycontrol.lab.** {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    !private <fields>;
    !private <methods>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Preserve Android Manifest entry points (Activities, Services, Receivers are kept by Android default,
# but ensure custom service classes are safely preserved without preventing internal method optimization)
-keep public class com.familycontrol.lab.FamilyAccessibilityService { public *; }
-keep public class com.familycontrol.lab.EnforcementService { public *; }
-keep public class com.familycontrol.lab.FamOrbitFirebaseMessagingService { public *; }
-keep public class com.familycontrol.lab.LabDeviceAdminReceiver { public *; }
-keep public class com.familycontrol.lab.BootReceiver { public *; }
-keep public class com.familycontrol.lab.TimeChangeReceiver { public *; }
-keep public class com.familycontrol.lab.EmergencySOSActivity { public *; }

# Allow obfuscation of all internal engines, UI composites, and business logic
-dontwarn org.json.**
