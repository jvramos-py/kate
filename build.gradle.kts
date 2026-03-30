plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.20"
}

group = "dev.notnull"
version = "1.0-SNAPSHOT"
val ktorVersion = "3.4.1"
val d4j = "3.3.1"

repositories {
    mavenCentral()
    maven {
        url = uri("https://m2.dv8tion.net/releases")
    }

}

dependencies {
    testImplementation(kotlin("test"))
    implementation("com.discord4j:discord4j-core:$d4j")
    implementation("com.discord4j:discord4j-voice:$d4j")
    implementation("dev.arbjerg:lavalink-client:3.4.0")
    implementation("io.ktor:ktor-client-cio:${ktorVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("io.ktor:ktor-client-content-negotiation:${ktorVersion}")
    implementation("io.ktor:ktor-serialization-kotlinx-json:${ktorVersion}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:1.8.0")
    implementation("io.projectreactor.kotlin:reactor-kotlin-extensions:1.2.2")
    implementation("ch.qos.logback:logback-classic:1.4.14")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}