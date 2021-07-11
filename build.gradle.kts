import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version Versions.kotlinVersion
    kotlin("plugin.serialization") version Versions.kotlinVersion
    id("net.mamoe.mirai-console") version Versions.miraiConsoleVersion
    id("com.github.johnrengelman.shadow") version Versions.shadowJarVersion
}

group = "com.chh2000day.mirai"
version = "0.2.1"

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(kotlin("stdlib-jdk8"))
    compileOnly("net.mamoe:mirai-core-api:${Versions.miraiCoreVersion}")
    compileOnly("net.mamoe:mirai-console:${Versions.miraiConsoleVersion}")
    compileOnly("com.squareup.okio:okio:${Versions.okioVersion}")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:${Versions.kotlinxSerializationVersion}")
    compileOnly("mysql:mysql-connector-java:${Versions.mysqlConnectorVersion}")
    compileOnly("com.squareup.okhttp3:okhttp:${Versions.okHttpVersion}")
    compileOnly("com.squareup.okhttp3:okhttp-tls:${Versions.okHttpVersion}")
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "11"
}