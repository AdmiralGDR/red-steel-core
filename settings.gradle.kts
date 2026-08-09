/*
 * RedSteelCore — pure Aetherium mod (Phase-17 gameplay monolith).
 *
 * EN: Plugin resolution draws the zero-config Aetherium Gradle plugin (`org.aetherium.gradle`) from
 *     mavenLocal, where the framework publishes it (`publishToMavenLocal`). No NeoForge ModDevGradle
 *     here anymore — the build is a plain `java-library` driven entirely by the Aetherium plugin,
 *     which emits the dual NeoForge/Fabric metadata and the universal jar.
 * RU: Разрешение плагинов берёт zero-config плагин Aetherium (`org.aetherium.gradle`) из mavenLocal,
 *     куда фреймворк его публикует. Никакого NeoForge ModDevGradle — сборка это обычный `java-library`,
 *     управляемый плагином Aetherium (двойные метаданные NeoForge/Fabric + универсальный jar).
 */
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Resolves the pinned Java 21 toolchain from local JDKs (the launching GraalVM 21).
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "redsteelcore"
