plugins {
    id("application")
    id("com.example.dependency-reports")
}

dependencies {
    implementation("org.apache.commons:commons-text:1.9")
    implementation(project(":utilities"))
}
