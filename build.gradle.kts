plugins {
    java
}

group = "com.bushsmp"
version = "1.1.0"

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
    // Paper/Purpur backend entrypoint.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.74-stable")

    // Optional APIs: the plugin's routing/filter modules do not require these at runtime.
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        exclude(group = "org.bukkit")
    }
    compileOnly("net.luckperms:api:5.5")
    compileOnly("dev.jorel:commandapi-paper-core:12.1.0")

    // PaperProxy companion entrypoint (Velocity 3.4 API baseline).
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.named<Jar>("jar") {
    archiveFileName.set("SuperpowerPlugin.jar")
    manifest {
        attributes(
            "Implementation-Title" to "SuperpowerPlugin",
            "Implementation-Version" to project.version
        )
    }
}
