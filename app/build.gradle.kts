import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Release signing comes from `keystore.properties` (never committed). A release without it is
 * refused outright: Android only replaces an installed app when the signature matches, so an APK
 * signed with any other key would strand every user on the version they already have.
 */
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

/** Fails a release build before anything is packaged, and leaves debug builds alone. */
fun requireReleaseKeystore() {
    if (keystorePropertiesFile.exists()) return
    throw GradleException(
        "Release signing is not configured. Create ${keystorePropertiesFile.path} from " +
            "keystore.properties.example and point storeFile at the project keystore. " +
            "A release signed with a different key cannot update an installed copy, so this " +
            "build stops here. Use assembleDebug for a local build with the debug key.",
    )
}

if (gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
    requireReleaseKeystore()
}

gradle.taskGraph.whenReady {
    val buildingRelease = allTasks.any { task ->
        task.name.contains("Release", ignoreCase = true) &&
            (task.name.startsWith("assemble") || task.name.startsWith("bundle") ||
                task.name.startsWith("package") || task.name.startsWith("install"))
    }
    if (buildingRelease) requireReleaseKeystore()
}

/**
 * Optional default resolver baked into the build. No instance ships with the source, and none has
 * to be committed to use one:
 *   ./gradlew :app:assembleRelease -Panyvideo.resolver=https://resolver.example.com
 * The value is recoverable from the APK, so protect an instance with a key and rate limits, not
 * by hiding this.
 */
val bakedResolver: String = providers.gradleProperty("anyvideo.resolver").orNull
    ?.trim()
    ?.trimEnd('/')
    .orEmpty()

android {
    namespace = "io.anyvideo.downloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.anyvideo.downloader"
        minSdk = 24
        targetSdk = 35
        versionCode = 10
        versionName = "1.3.1"

        buildConfigField("String", "DEFAULT_RESOLVER_API", "\"$bakedResolver\"")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // requireReleaseKeystore() above stops the build when this is null.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
