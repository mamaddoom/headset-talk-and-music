plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mamaddoom.ridetalk"
    compileSdk = 35

    defaultConfig {
        // Online beta: its own app id, so it installs next to the main Helmet Media app.
        applicationId = "com.mamaddoom.helmetmedia.online"
        minSdk = 26
        targetSdk = 35
        // CI build number, so every downloaded APK installs over the previous one.
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.0.$build"
    }

    signingConfigs {
        // Optional release key supplied by CI secrets; falls back to the debug key.
        val ksPath = System.getenv("RIDETALK_KEYSTORE")
        if (ksPath != null && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                if (ksPath.endsWith(".p12")) storeType = "pkcs12"
                storePassword = System.getenv("RIDETALK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RIDETALK_KEY_ALIAS")
                keyPassword = System.getenv("RIDETALK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
        buildConfig = false
    }
    lint {
        // Lint runs as its own CI step; don't let it block producing an APK.
        checkReleaseBuilds = false
        abortOnError = false
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    implementation("io.github.jaredmdobson:concentus:1.0.2")
    testImplementation("junit:junit:4.13.2")
}
