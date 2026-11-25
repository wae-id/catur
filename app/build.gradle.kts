android {
    namespace = "com.wad.chessoverlay"
    compileSdk = 34

    defaultConfig {
        // ... (biarin aja sama)
    }

    // TAMBAHIN BLOK INI WAD
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        // ... (biarin aja sama)
    }
    compileOptions {
        // ... (biarin aja sama)
    }
    kotlinOptions {
        // ... (biarin aja sama)
    }
}
