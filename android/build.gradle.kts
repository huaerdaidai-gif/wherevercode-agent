// Top-level build file: plugin versions for the Android spike.
// Keep in sync with .github/workflows/android.yml (AGP / Gradle / JDK).
plugins {
    id("com.android.application") version "8.1.4" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
    id("org.jetbrains.kotlin.kapt") version "1.9.0" apply false
    id("com.chaquo.python") version "15.0.1" apply false
}
