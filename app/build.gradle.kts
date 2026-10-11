import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// The prompts live at the repo root (prompts/*.md) because the Python checkers and the Kotlin app must
// read the very same files; the prompt contract travels with them, so the in-app prompt editor validates an
// override against the same contracts/prompts.json that CI enforces. This copies both into the APK's assets.
abstract class CopyPromptAssets : DefaultTask() {
    @get:InputDirectory abstract val prompts: DirectoryProperty

    @get:InputDirectory abstract val contracts: DirectoryProperty

    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @get:Inject abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            from(prompts) { include("*.md"); into("prompts") }
            from(contracts) { include("prompts.json", "states.json", "embedding_vectors.json"); into("contracts") }
            into(outputDir)
        }
    }
}

val syncPrompts = tasks.register<CopyPromptAssets>("syncPrompts") {
    prompts.set(rootProject.file("prompts"))
    contracts.set(rootProject.file("contracts"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(syncPrompts, CopyPromptAssets::outputDir)
    }
}

android {
    namespace = "com.clarity.life"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.clarity.life"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // ONNX Runtime and SQLCipher ship native code for four kinds of processor. Phones like the owner's
            // are arm64, so that is all a release carries. The debug build below adds x86_64 for the CI emulator.
            abiFilters += setOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            // Signing material comes from GitHub Secrets (CLAUDE.md, Git and release). Never from the repo.
            val path = System.getenv("KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // The CI emulator is x86_64; only the debug build is run there.
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (!System.getenv("KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    sourceSets.getByName("androidTest") {
        assets.srcDir("$projectDir/schemas")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // The JVM contract tests read the very same files the Python reference tests read,
            // so the app and the spec cannot drift (CLAUDE.md, The contracts).
            it.systemProperty("clarity.repoRoot", rootProject.projectDir.absolutePath)
        }
    }
    lint {
        warningsAsErrors = false
        abortOnError = true
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}

// Room writes the schema of every database version to app/schemas, which is committed: the migration tests
// and SchemaTest read those files, and CI fails if a build exports a schema that is not in the repository.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher)
    implementation(libs.androidx.sqlite)
    implementation(libs.androidx.sqlite.ktx)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.room.testing)
}
