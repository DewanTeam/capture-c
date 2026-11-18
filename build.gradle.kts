plugins {
    id("java")
}

group = "com.bushsmp"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

repositories {
    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    mavenCentral()
    maven {
        url = uri("https://jitpack.io")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.62-stable")
    compileOnly("com.github.MilkBowl:VaultAPI:1.7.1") {
        exclude(group = "org.bukkit")
    }
}

tasks.named<Jar>("jar") {
    archiveFileName.set("SuperpowerPlugin.jar")
}
