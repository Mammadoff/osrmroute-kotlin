plugins {
    id("com.android.application") version "8.9.1"
    kotlin("android") version "2.1.10"
    kotlin("jvm") version "2.1.10" apply false
    kotlin("plugin.serialization") version "2.1.10" apply false
}
android {
    namespace = "com.osrmroute.example"
    compileSdk = providers.gradleProperty("compileSdk").orElse("35").get().toInt()
    defaultConfig {
        applicationId = "com.osrmroute.example"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(project(":osrmroute-kotlin")) }
