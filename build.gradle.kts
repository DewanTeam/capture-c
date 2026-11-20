plugins {
    java
}

group = "com.bushsmp"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.62-stable")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        exclude(group = "org.bukkit")
    }
    compileOnly("net.luckperms:api:5.5")
    compileOnly("dev.jorel:commandapi-paper-core:12.1.0")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.named<Jar>("jar") {
    archiveFileName.set("SuperpowerPlugin.jar")
}
