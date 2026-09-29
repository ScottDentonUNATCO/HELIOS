plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockwebserver)
}

tasks.withType<Test> {
    useJUnit()
}

// Build with a JDK 17+ (the toolchain provides it); bytecode targets 17.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    kotlinOptions {
        jvmTarget = "17"
    }
}
