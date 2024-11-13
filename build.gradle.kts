plugins {
    kotlin("jvm") version "1.9.23"
    id("maven-publish")
}

group = "com.github.iamcalledrob"
version = "1.0.3"

repositories {
    mavenCentral()
}

java {
    withSourcesJar()
}

kotlin {
    jvmToolchain(17)

    dependencies {
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}