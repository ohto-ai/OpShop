plugins {
    java
    id("com.gradleup.shadow") version "8.3.0"
}

group = "com.ohtoai"
version = project.findProperty("plugin_version")?.toString() ?: "1.0.0"
description = "Multi-currency virtual shop with custom item support"

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
    maven("https://repo.rosewooddev.io/repository/public/")
}

dependencies {
    // Paper API (Bukkit + Adventure components, etc.)
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")

    // ---- Shaded (bundled in JAR) ----
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.mysql:mysql-connector-j:8.3.0")

    // ---- Provided by other plugins (compile only) ----
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") { isTransitive = false }
    compileOnly("org.black_ixx:playerpoints:3.2.7") { isTransitive = false }
    compileOnly("me.clip:placeholderapi:2.11.6") { isTransitive = false }
}

tasks.shadowJar {
    archiveFileName.set("${project.name}-${project.version}.jar")
    relocate("com.zaxxer.hikari", "com.ohtoai.opshop.libs.hikari")
    relocate("com.mysql.cj", "com.ohtoai.opshop.libs.mysql")
}

tasks.compileJava {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(
            "version" to project.version,
            "name" to project.name,
            "description" to project.description
        )
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
