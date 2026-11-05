plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ro.noraz.nhomeapp"
    compileSdk = 35
    defaultConfig {
        applicationId = "ro.noraz.nhomeapp"
        minSdk = 28
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.5"
    }
    signingConfigs {
        create("stable") {
            // Public repo: this key only signs a WebView shell with no secrets inside.
            storeFile = file("nhome-app.jks")
            storePassword = "nhomeapp"
            keyAlias = "nhomeapp"
            keyPassword = "nhomeapp"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
