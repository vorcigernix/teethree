import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.services) apply false
}

val firebaseConfigInput =
    providers
        .gradleProperty("T3CODE_ANDROID_GOOGLE_SERVICES_FILE")
        .orElse(providers.environmentVariable("T3CODE_ANDROID_GOOGLE_SERVICES_FILE"))
        .orNull
        ?.trim()
        ?.takeIf(String::isNotEmpty)
val firebaseConfigFile =
    firebaseConfigInput?.let(::file) ?: file("google-services.json").takeIf { it.isFile }

if (firebaseConfigInput != null)
    require(firebaseConfigFile?.isFile == true) { "The configured Firebase file does not exist." }

firebaseConfigFile?.let { config ->
    pluginManager.apply("com.google.gms.google-services")
    tasks.withType<com.google.gms.googleservices.GoogleServicesTask>().configureEach {
        googleServicesJsonFiles.set(listOf(config))
    }
}

android {
    namespace = "com.example.teethree"
    compileSdk {
        version = release(37)
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.t3tools.t3code.dev"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val publicDefaults =
            mapOf(
                "T3CODE_CLERK_PUBLISHABLE_KEY" to "pk_live_Y2xlcmsudDMuY29kZXMk",
                "T3CODE_CLERK_JWT_TEMPLATE" to "t3-relay",
                "T3CODE_RELAY_URL" to "https://relay.t3.codes",
            )
        listOf("T3CODE_CLERK_PUBLISHABLE_KEY", "T3CODE_CLERK_JWT_TEMPLATE", "T3CODE_RELAY_URL")
            .forEach { name ->
                buildConfigField(
                    "String",
                    name,
                    groovy.json.JsonOutput.toJson(
                        providers
                            .gradleProperty(name)
                            .orElse(providers.environmentVariable(name))
                            .orElse(
                                providers.environmentVariable(
                                    if (name == "T3CODE_CLERK_PUBLISHABLE_KEY")
                                        "EXPO_PUBLIC_CLERK_PUBLISHABLE_KEY"
                                    else if (name == "T3CODE_CLERK_JWT_TEMPLATE")
                                        "EXPO_PUBLIC_CLERK_JWT_TEMPLATE"
                                    else name
                                )
                            )
                            .orElse(publicDefaults.getValue(name))
                            .get()
                    ),
                )
            }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Werror")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Personal test builds: sign with the debug key so a release installs over a debug
            // install (and vice versa) without losing data. Swap in a real key before publishing.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].apply {
        kotlin.directories += "../third_party/ghostty/kotlin"
        jniLibs.directories += "../third_party/ghostty/jniLibs"
        assets.directories += "../third_party/ghostty/assets"
    }

    externalNativeBuild {
        cmake {
            path = file("../third_party/ghostty/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)
    implementation(libs.androidx.material3.adaptive.navigation)
    implementation(libs.androidx.material3.navigation.suite)
    implementation(libs.androidx.window)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.commonmark)
    implementation(libs.ratex.android)
    implementation(libs.firebase.messaging)
    implementation(libs.clerk.android.ui)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.okhttp.tls)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.window.testing)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
