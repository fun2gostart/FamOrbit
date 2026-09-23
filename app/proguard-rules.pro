# FamOrbit R8 / ProGuard Configuration

# Kotlin & Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# AndroidX & Jetpack Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class androidx.lifecycle.** { *; }
-keep class androidx.work.** { *; }

# Firebase Cloud Messaging
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# FamOrbit App Core & Data Models
-keep class com.familycontrol.lab.** { *; }
-keepclassmembers class com.familycontrol.lab.** { *; }

# JSON Serialization / Org Json
-keep class org.json.** { *; }
