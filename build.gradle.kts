// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
}

subprojects {
    tasks.withType<Test>().configureEach {
        maxParallelForks = 1
        jvmArgs("-Xmx512m", "-XX:+UseSerialGC")
    }
}
