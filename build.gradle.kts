plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Android shared storage (/storage/emulated/0) is a FUSE mount that cannot
// service the delete pattern Gradle uses when it cleans a task output
// directory, so `mergeDebugResources` fails with "Unable to delete directory"
// on a checkout that lives there. Set PINCATCHER_BUILD_DIR to move build output
// onto internal storage:
//
//     PINCATCHER_BUILD_DIR=$PREFIX/tmp/pc-build ./gradlew :app:assembleDebug
//
// Unset in CI and on a normal filesystem, where the default is correct.
providers.environmentVariable("PINCATCHER_BUILD_DIR").orNull?.let { root ->
    val base = File(root)
    allprojects {
        layout.buildDirectory.set(File(base, name))
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}