plugins {
    java
}

group = "com.anobank"
version = "4.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.named<Jar>("jar") {
    archiveFileName.set("ANOBank-PaperProxy-Manager.jar")
    manifest {
        attributes(
            "Main-Class" to "com.anobank.manager.ManagerMain",
            "Implementation-Title" to "ANOBank PaperProxy Manager",
            "Implementation-Version" to project.version
        )
    }
}
