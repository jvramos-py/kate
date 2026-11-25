plugins {
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.serialization") version "2.4.21"
    application
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "dev.notnull"
version = "1.0.0"

val ktorVersion = "3.6.1"
val discord4jVersion = "3.3.3"
val exposedVersion = "0.61.0"
val coroutinesVersion = "1.11.0"
val serializationVersion = "1.13.0"
val libdaveVersion = "0.2.0"

repositories {
    mavenCentral()
    maven("https://m2.dv8tion.net/releases")
}

dependencies {
    implementation("com.discord4j:discord4j-core:$discord4jVersion")
    implementation("com.discord4j:discord4j-voice:$discord4jVersion")
    implementation("moe.kyokobot.libdave:impl-jni:$libdaveVersion")
    runtimeOnly("moe.kyokobot.libdave:natives-linux-x86-64:$libdaveVersion")
    runtimeOnly("moe.kyokobot.libdave:natives-linux-musl-x86-64:$libdaveVersion")

    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    implementation("ch.qos.logback:logback-classic:1.6.8")
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("org.jetbrains.exposed:exposed-core:$exposedVersion")
    implementation("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    implementation("com.pgvector:pgvector:0.1.6")

    implementation("io.github.jaredmdobson:concentus:1.0.2")
    implementation("org.jsoup:jsoup:1.24.1")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.notnull.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

ktlint {
    version.set("1.8.0")
}

tasks.register<JavaExec>("ingestRag") {
    group = "rag"
    description = "Baixa páginas da Fandom, gera chunks e embeddings no Postgres"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.notnull.RagIngestKt")
}
