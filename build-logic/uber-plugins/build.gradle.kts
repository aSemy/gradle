plugins {
    id("gradlebuild.build-logic.kotlin-dsl-gradle-plugin")
}

description = "Provides plugins that combine and configure other plugins for different kinds of Gradle subprojects"

dependencies {
    implementation(project(":basics"))
    implementation(project(":binary-compatibility"))
    implementation(project(":buildquality"))
    implementation(project(":cleanup"))
    implementation(project(":dependency-modules"))
    implementation(project(":jvm"))
    implementation(project(":profiling"))
    implementation(project(":publishing"))

    implementation("org.gradle.kotlin:gradle-kotlin-dsl-conventions")
    implementation(kotlin("gradle-plugin"))
}
