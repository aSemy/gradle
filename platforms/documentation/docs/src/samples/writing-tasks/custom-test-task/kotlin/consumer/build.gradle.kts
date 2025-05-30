plugins {
    application
}

// tag::use-tooling-api[]
repositories {
    maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
}

dependencies {
    implementation("org.gradle:gradle-tooling-api:8.14")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
// end::use-tooling-api[]

application {
    mainClass = "com.example.sample.Main"
}
tasks {
    "run"(JavaExec::class) {
        args(gradle.gradleHomeDir!!.absolutePath, gradle.parent!!.rootProject.layout.projectDirectory.asFile.absolutePath)
    }
}
