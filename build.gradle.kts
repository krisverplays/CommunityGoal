plugins {
    java
}

group = "dev.domin8r"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

// Flat layout: all files sit in the repo root (easy to upload from a phone).
sourceSets["main"].java.setSrcDirs(listOf("."))
sourceSets["main"].java.include("*.java")
sourceSets["main"].resources.setSrcDirs(listOf("."))
sourceSets["main"].resources.include("plugin.yml", "config.yml")

tasks.jar {
    archiveBaseName.set("CommunityGoal")
    archiveVersion.set("")
}
