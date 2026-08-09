/*
 * RedSteelCore — pure Aetherium mod (the framework "Gameplay & UI Monolith").
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 *
 * EN: The whole build is ONE DSL block. `org.aetherium.gradle` applies `java-library`, pins the Java 21
 *     toolchain with `--enable-preview`, wires the Maven repos, adds the Aetherium API dependencies
 *     (core + edge + content + its annotation processor), auto-generates the host-loader metadata
 *     (neoforge.mods.toml + fabric.mod.json), and — with `universal = true` — registers the
 *     `aetheriumUniversalJar` task producing a single dual-loader jar. There is NO `net.neoforged.moddev`
 *     plugin and NO Minecraft/NeoForge on the compile classpath: this mod is built purely against the
 *     loader-agnostic Aetherium API.
 * RU: Вся сборка — ОДИН DSL-блок. `org.aetherium.gradle` подключает `java-library`, тулчейн Java 21 с
 *     `--enable-preview`, репозитории, зависимости API Aetherium (core + edge + content + процессор),
 *     автогенерирует метаданные загрузчика и (при `universal = true`) регистрирует задачу
 *     `aetheriumUniversalJar` — единый jar под оба загрузчика. Никакого `net.neoforged.moddev` и ни
 *     одного типа Minecraft/NeoForge на classpath компиляции.
 */
plugins {
    id("org.aetherium.gradle") version "1.0.0-SNAPSHOT"
}

group = "redstone.team"
version = "1.0.0-alpha1"

aetherium {
    // The Aetherium framework version to resolve from mavenLocal (core/edge/content/ui/network/loader).
    version = "1.0.0-SNAPSHOT"
    modId = "redsteelcore"
    displayName = "Red Steel Core"

    // Foolproof single-file distribution: one redsteelcore-universal.jar with the Aetherium runtime
    // (core + ui + network + edge + content) embedded and BOTH neoforge.mods.toml + fabric.mod.json.
    universal = true

    // aetherium-loader is the per-loader bridge mod; the framework does NOT publish it as an embeddable
    // library artifact (it is not in `publishableModules`). It ships as its own drop-in mod that supplies
    // the PlatformBridge at runtime via ServiceLoader. So we do not try to embed it here.
    embedLoader = false

    // The Sovereign Shield (the framework): protect the mod's OWN classes against reverse-engineering
    // and automated (AI/LLM) analysis before packaging — strip debug, encrypt string literals, opaque
    // control flow, rename classes + private members (keep-list preserves service/by-name resolution),
    // add an integrity manifest and an author watermark. Each pass runs in a verification sandbox, so an
    // unprotectable class reverts cleanly and the build never breaks.
    // EN: On by default. `-PrscNoShield` builds a readable variant: obfuscated stack traces read as
    //     `o.at.a()`, so if something misbehaves in game, rebuild with that flag and the crash report
    //     names real classes again.
    // RU: Включён по умолчанию. `-PrscNoShield` собирает читаемый вариант: в обфусцированных стектрейсах
    //     видно только `o.at.a()`, поэтому при проблеме в игре пересоберите с этим флагом.
    shield = !project.hasProperty("rscNoShield")
    shieldAuthor = "RedstoneTeam"

    // Class/member renaming is ON again. It was disabled in the previous round because the Shield renamed
    // classes without rewriting the content processor's `META-INF/aetherium/content.index`, producing a
    // green build and a jar whose block could never load. the framework fixed exactly that (the index
    // is rewritten through the rename map, with a fail-loud guard), so the full protection set applies:
    // debug stripping, string-literal encryption, opaque control flow, junk/decoy methods, class and
    // private-member renaming, the integrity manifest and the author watermark. The self-test's classload
    // smoke check is what keeps this honest.
    shieldRename = !project.hasProperty("rscNoShield")
}

dependencies {
    // The plugin already wires aetherium-core + aetherium-edge + aetherium-content (impl + annotationProcessor).
    // This mod additionally uses the declarative UI, the hierarchical tree-sync network module, and — as of
    // the framework — the framework's config store (replacing the mod's hand-written config package).
    implementation("org.aetherium:aetherium-ui:1.0.0-SNAPSHOT")
    implementation("org.aetherium:aetherium-network:1.0.0-SNAPSHOT")
    implementation("org.aetherium:aetherium-config:1.0.0-SNAPSHOT")
}

// The content annotation processor emits its generated assets (blockstate/model/loot/lang) into the
// compile output, which also flows in as resources — so the same path can appear twice. The plugin's
// universal/bundle jars already de-dup; mirror that on the default `jar` so `./gradlew build` is green too.
tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Zero-warning mandate. Full lint is on; `-preview` is disabled because this mod is deliberately a
// preview build (Panama FFM in the anomaly engine), so those notes are expected and not defects.
// `-processing` is off too: every annotation-processing diagnostic here is about the framework's own
// processor acting on this mod's annotations, not about code this mod owns.
//
// One diagnostic remains and cannot be removed on the consumer side: the Aetherium content processor
// writes the mod's entrypoint class in the final annotation round, which makes javac emit the MANDATORY
// note "created in the last round will not be subject to annotation processing". It is not gated by any
// -Xlint category (only the blunt `-Xlint:none` hides it, which would also hide real warnings), and it
// concerns a framework-generated file, not this mod's sources. Rather than blind the compiler, the mod's
// own sources are held to zero warnings by `scripts/check-warnings.sh`, which compiles from clean and
// fails on any warning except that single whitelisted note. It runs in CI on every push.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-preview", "-Xlint:-processing"))
}

// NOTE: the two build workarounds this script used to carry are gone, because the framework fixed
// both at the source: the Shield now writes to a task-owned `build/aetherium/shielded` mirror instead of
// mutating the compile output (so `./gradlew build` twice in a row works and the class-reading tasks below
// no longer need a forced ordering), and every packaging task — `jar`, `aetheriumBundle` and
// `aetheriumUniversalJar` — consumes that mirror and fails loudly if `shield = true` produced no integrity
// manifest (so the universal jar can no longer ship unprotected).

// EN: The verification harness. No test framework is on the classpath (the plugin wires a plain
//     `java-library`, and pulling JUnit into an otherwise dependency-free mod is not worth it), so the
//     checks run from a `main` like the framework's own `*SelfTest` classes do.
// RU: Харнесс проверки. Тестового фреймворка на classpath нет (плагин подключает обычный `java-library`,
//     а тянуть JUnit в мод без зависимостей не стоит), поэтому проверки запускаются из `main`, как это
//     сделано в самих `*SelfTest` фреймворка.
tasks.register<JavaExec>("runSelfTest") {
    group = "verification"
    description = "Runs the RedSteelCore self-test (config, network, restrictions, mechanics, UI, packaged jar)."
    // Behaviour is verified against the compile output; the shipped artifact is verified separately by
    // `verifyJar`, which reads the jar as a zip. Since the framework the Shield no longer touches these classes.
    dependsOn("testClasses")
    mainClass.set("redstone.team.redsteelcore.RedSteelSelfTest")
    classpath = sourceSets["test"].runtimeClasspath
    // Preview for the FFM anomaly engine; the incubator Vector module for the framework's SIMD paths.
    jvmArgs("--enable-preview", "--add-modules=jdk.incubator.vector")
    // Forward -Prsc.debugUi=true so the UI audit can be asked to print each overflow.
    if (project.hasProperty("rsc.debugUi")) {
        systemProperty("rsc.debugUi", "true")
    }
}

// EN: Verify the artifact that actually ships — lang merging and the Sovereign Shield only happen at
//     packaging time, and both have previously produced a green build with a broken jar.
// RU: Проверка реально поставляемого артефакта — слияние lang и работа Щита происходят только при упаковке,
//     и оба уже приводили к «зелёной сборке со сломанным jar».
tasks.register<JavaExec>("verifyJar") {
    group = "verification"
    description = "Verifies the packaged universal jar (lang merge, shield, name-based indexes, dist safety)."
    dependsOn("testClasses", "aetheriumUniversalJar", "packageForGame")
    mainClass.set("redstone.team.redsteelcore.RedSteelJarVerify")
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs("--enable-preview", "--add-modules=jdk.incubator.vector")
    args(layout.buildDirectory.file("libs/${project.name}-${project.version}-universal.jar")
        .get().asFile.absolutePath,
        layout.buildDirectory.dir("game-package").get().asFile.absolutePath)
}

// EN: A visual proof for humans — render both screens to PNG with a pure-JDK Graphics2D UiRenderer (no
//     Minecraft), so layout, fit and legibility can be reviewed without launching the game — at several
//     widths, which is how the responsive `build(Rect)` layout is checked by eye.
// RU: Визуальное доказательство для человека — рендер обоих экранов в PNG чистым JDK-Graphics2D
//     UiRenderer (без Minecraft), чтобы увидеть вёрстку/влезание текста/читаемость без запуска игры.
// EN: Redraws the block texture into src/main/resources. Deterministic, so re-running it changes nothing
//     unless the pattern changed; the PNG is committed, not generated at build time.
// RU: Перерисовывает текстуру блока в ресурсы. Детерминирован; PNG коммитится, а не генерируется сборкой.
tasks.register<JavaExec>("drawBlockTexture") {
    group = "build"
    description = "Redraws assets/redsteelcore/textures/block/anomaly_core.png (16x16)."
    dependsOn("testClasses")
    mainClass.set("redstone.team.redsteelcore.AnomalyCoreTexture")
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs("--enable-preview", "--add-modules=jdk.incubator.vector")
}

tasks.register<JavaExec>("renderUiPreview") {
    group = "verification"
    description = "Render the faction/config screens to PNG via a pure-JDK Graphics2D UiRenderer."
    dependsOn("testClasses")
    mainClass.set("redstone.team.redsteelcore.RedSteelUiPreview")
    classpath = sourceSets["test"].runtimeClasspath
    jvmArgs("--enable-preview", "--add-modules=jdk.incubator.vector")
    args(project.findProperty("outDir") ?: layout.buildDirectory.dir("ui-preview").get().asFile.absolutePath)
}

// ---------------------------------------------------------------------------------------------------
// EN: Assemble a folder that can be dropped straight into a Minecraft `mods/` directory.
//     RedSteelCore is a PURE Aetherium mod — it carries no loader code — so it ships alongside the two
//     framework artifacts. the framework made both of them correct on its own side, so this is now a
//     plain copy: the loader embeds its runtime via Jar-in-Jar, the transformer carries the ModLauncher
//     boot services, and the roles (MOD / GAMELIBRARY) no longer collide. Every repackaging workaround this
//     script used to carry — a fat loader jar, stripping ASM, stripping ModLauncher services, stripping our
//     own embedded runtime — is gone with it.
// RU: Собирает папку, которую можно скопировать прямо в `mods/`. RedSteelCore — ЧИСТЫЙ мод на Aetherium и
//     поставляется вместе с двумя артефактами фреймворка. the framework исправил их на своей стороне, поэтому
//     здесь теперь обычное копирование, а все прежние обходные пути перепаковки удалены.
// ---------------------------------------------------------------------------------------------------
val frameworkDir = providers.gradleProperty("aetheriumDir")
    .orElse(File(rootDir.parentFile.parentFile.parentFile, "AetheriumFramework").absolutePath)

val buildFrameworkJars = tasks.register<Exec>("buildFrameworkJars") {
    group = "distribution"
    description = "Builds the aetherium loader + transformer mod jars from the framework checkout."
    workingDir = File(frameworkDir.get())
    commandLine("./gradlew", ":aetherium-loader:jar", ":aetherium-transformer:jar", "-q")
}

tasks.register<Copy>("packageForGame") {
    group = "distribution"
    description = "Assembles build/game-package/ — the three mod jars plus INSTALL.md, ready for mods/."
    dependsOn("aetheriumUniversalJar", buildFrameworkJars)

    val outDir = layout.buildDirectory.dir("game-package")
    into(outDir)
    // Copy never removes what it did not write; a stale jar from an earlier layout sitting next to the
    // new set would leave the user guessing which files to install (or installing both).
    doFirst { delete(outDir) }

    from(layout.buildDirectory.file("libs/${project.name}-${project.version}-universal.jar"))
    from(File(frameworkDir.get(), "aetherium-loader/build/libs")) {
        include("aetherium-loader-*.jar")
        exclude("*-sources.jar", "*-javadoc.jar")
    }
    from(File(frameworkDir.get(), "aetherium-transformer/build/libs")) {
        include("aetherium-transformer-*.jar")
        exclude("*-sources.jar", "*-javadoc.jar")
    }

    doLast {
        val shielded = !project.hasProperty("rscNoShield")
        File(outDir.get().asFile, "INSTALL.md").writeText(
            """
            # Red Steel Core — установка и проверка

            Сборка: **${project.version}**${if (shielded) " (защищённая)" else " (отладочная, без обфускации)"}

            ## Что нужно
            - Minecraft **1.21.1**
            - **NeoForge 21.1.x**

            ## Установка
            Скопируйте **ВСЕ ТРИ** `.jar` из этой папки в `mods/` вашей сборки:

            | Файл | Что это |
            | --- | --- |
            | `aetherium-transformer-*.jar` | загрузочный слой Aetherium (ModLauncher). Грузится раньше всех. |
            | `aetherium-loader-*.jar` | движок Aetherium: регистрация контента, команды, интерфейс. |
            | `redsteelcore-*-universal.jar` | сам мод. |

            Все три обязательны: RedSteelCore не содержит кода загрузчика и без него не сделает ничего.

            ## С чего начать в игре
            1. Нажмите **G** и выберите фракцию (клавиша меняется в *Настройки → Управление*).
            2. Возьмите **Аномальное Ядро** — вкладка креатива «Red Steel Core»
               (или `/give @s redsteelcore:anomaly_core`).
            3. **Поставьте его** — чанк сразу закрепляется за вашей фракцией.
            4. Ядро копит эссенцию само, тем быстрее, чем сильнее аномалия. Возвращайтесь и **кликайте по
               нему правой кнопкой**, чтобы забрать накопленное.
            5. Сломаете ядро — чанк освободится.

            ## Команды
            | Команда | Права | Что делает |
            | --- | --- | --- |
            | `/redsteel gui` | все | открыть интерфейс |
            | `/redsteel faction` | все | фракция, эссенция, квота, счётчики |
            | `/redsteel join <samurai\|communists>` | все | вступить во фракцию |
            | `/redsteel leave` | все | выйти из фракции |
            | `/redsteel essence [n]` | чтение — все, изменение — 2 | баланс эссенции |
            | `/redsteel config get\|set <ключ> <знач>\|reload` | 2 | настройки сервера |
            | `/redsteel claim <фракция>` | 2 | закрепить чанк вручную |

            ## Механики фракций
            - **Орден Самураев** — «Концентрация»: побеждайте бойцов враждебной фракции, счётчик растёт;
              **любой полученный урон обнуляет его**. Заполнили — получили эссенцию.
            - **Кибер-Коммуна** — «Кинетический Резонанс»: добывайте блоки цепочкой в пределах таймера.
              Блоки, установленные вами, комбо не растят.

            ## Настройки
            `config/redsteelcore.json` создаётся при первом запуске и **перечитывается на лету**.
            Испорченный файл отклоняется целиком — прежние значения остаются в силе. Те же значения
            правятся в игре (интерфейс → «Настройки сервера», права 2) и командой `/redsteel config`.

            ## Необязательно: `--enable-preview`
            Мод полностью работает без этого флага. Но off-heap движок аномалий (FFM/Panama) собран с
            preview-возможностями Java, и без флага он **выключается**: ядра копят эссенцию по базовой
            ставке, без ускорения от интенсивности аномалии. Всё остальное — фракции, механики, территории,
            интерфейс — не зависит от него. Хотите полную механику — добавьте в аргументы JVM:

                --enable-preview

            В Modrinth App: *Профиль → Options → Java → JVM arguments*.

            ## Если что-то пошло не так
            ${if (shielded)
                "Классы защищены обфускацией, поэтому в стектрейсе будут имена вида `o.at.a()`.\n" +
                "            Пересоберите отладочную версию с читаемыми именами:\n\n" +
                "                ./gradlew packageForGame -PrscNoShield\n"
              else
                "Это отладочная сборка: имена классов настоящие, стектрейсы читаются напрямую.\n"}
            """.trimIndent()
        )
        logger.lifecycle("Game package ready: ${outDir.get().asFile}")
    }
}
