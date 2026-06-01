plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.sound2inat.storage"
    compileSdk = 35

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("test").assets.srcDir("$projectDir/schemas")
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    room {
        schemaDirectory("$projectDir/schemas")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

detekt {
    config.setFrom(files("$rootDir/detekt.yml"))
    buildUponDefaultConfig = true
    autoCorrect = true
}

// Workaround for the AGP / Room interaction where mergeDebugAssets is computed
// up-to-date before Room exports a freshly-bumped schema (e.g. 11.json), and
// MigrationTest then fails to locate the schema file. Force the merged-assets
// directory to contain every checked-in schema before unit tests run.
val schemaSourceDir = layout.projectDirectory.dir("schemas")
val schemaMergeOutputDir = layout.buildDirectory.dir(
    "intermediates/assets/debug/mergeDebugAssets"
)
val copyRoomSchemasIntoAssets = tasks.register<Copy>("copyRoomSchemasIntoAssets") {
    from(schemaSourceDir)
    into(schemaMergeOutputDir)
}
// Anything that bundles or reads merged test assets must run after the copy
// task; mergeDebugAssets itself stays the source of truth and only gets
// supplemented (the copy writes alongside its outputs).
tasks.matching {
    it.name == "testDebugUnitTest" ||
        it.name == "packageDebugUnitTestForUnitTest" ||
        it.name == "compressDebugAssets" ||
        it.name == "mergeDebugUnitTestAssets"
}.configureEach {
    dependsOn(copyRoomSchemasIntoAssets)
}
copyRoomSchemasIntoAssets.configure {
    mustRunAfter("mergeDebugAssets")
}

dependencies {
    api(project(":inference"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.truth)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.core.testing)
    testImplementation(libs.room.testing)

    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.7")
}
