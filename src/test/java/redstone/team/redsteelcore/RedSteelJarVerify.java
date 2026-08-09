/*
 * RedSteelCore — pure Aetherium mod.
 * Copyright (C) 2026 RedstoneTeam <admiralgdr@gmail.com>. Licensed under GNU AGPLv3.
 */
package redstone.team.redsteelcore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Verifies the <em>packaged jar</em> — the artifact that actually ships.
 *
 * <p>EN: Several properties exist only after packaging and cannot be observed on the compile classpath: the
 * Gradle plugin merges the AP-generated and hand-written lang files at jar time, and the Sovereign Shield
 * rewrites classes (and, with renaming on, the name-based content index) as a pre-jar step. Both have
 * previously produced a <em>green build with a broken artifact</em>, so they are checked against the real
 * zip rather than inferred.
 *
 * <p><strong>Why this is a separate program from {@code RedSteelSelfTest}.</strong> The Shield rewrites
 * {@code build/classes/java/main} <em>in place</em>, so once it has run, anything executing from that
 * directory can no longer resolve the mod's classes under their original names. This class therefore
 * imports <em>nothing</em> from the mod and reads the jar purely as a zip, which makes it safe to run after
 * packaging; the behavioural suite runs before it, against unshielded classes.
 *
 * <p>RU: Часть свойств появляется только при упаковке и не видна на classpath компиляции: плагин сливает
 * lang-файлы во время сборки jar, а Суверенный Щит переписывает классы (и, при переименовании, текстовый
 * индекс контента) перед упаковкой. И то и другое уже приводило к «зелёной сборке со сломанным артефактом»,
 * поэтому проверяется по реальному zip. Отдельная программа нужна потому, что Щит переписывает
 * {@code build/classes/java/main} НА МЕСТЕ: после него код, запущенный из этого каталога, не находит классы
 * мода под исходными именами. Здесь нет ни одного импорта мода — только чтение zip.
 */
public final class RedSteelJarVerify {

    private static final String MOD_ID = "redsteelcore";

    private static int failures;

    private RedSteelJarVerify() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0 || !Files.isRegularFile(Path.of(args[0]))) {
            System.out.println("  FAIL packaged jar not found: " + (args.length == 0 ? "<no path>" : args[0]));
            System.exit(1);
            return;
        }
        Path jar = Path.of(args[0]);
        System.out.println("Verifying " + jar.getFileName());

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            langFilesAreMerged(zip);
            nameBasedIndexesResolve(zip);
            entrypointIsPackaged(zip);
            distIsSafe(zip);
            bothLoadersAreDescribed(zip);
            shieldWasApplied(zip);
            blockTextureIsPresent(zip);
        }

        // The distribution folder, if it was built — this is what a player actually installs.
        if (args.length > 1) {
            gamePackageIsInstallable(Path.of(args[1]));
        }

        System.out.println();
        System.out.println("JAR RESULT: " + (failures == 0 ? "PASS ✓" : "FAIL ✗ (" + failures + " check(s))"));
        if (failures != 0) {
            System.exit(1);
        }
    }

    /** Both shipped languages must carry the same keys — the plugin merges AP-generated + hand-written. */
    private static void langFilesAreMerged(ZipFile zip) throws IOException {
        Set<String> en = langKeys(zip, "assets/" + MOD_ID + "/lang/en_us.json");
        Set<String> ru = langKeys(zip, "assets/" + MOD_ID + "/lang/ru_ru.json");
        check("en_us.json is packaged and non-empty", !en.isEmpty());
        check("the AP-generated block key survived the merge",
                en.contains("block." + MOD_ID + ".anomaly_core"), en.toString());
        check("the hand-written keys survived the merge",
                en.contains("faction." + MOD_ID + ".samurai"), en.toString());
        check("both languages carry identical key sets", en.equals(ru), "en=" + en + " ru=" + ru);
    }

    /**
     * Every class named in a framework text index must exist in the jar under exactly that name.
     *
     * <p>This is the check that catches class renaming breaking name-based resolution: the build stays
     * green, but a missing entry here means the block never registers at runtime.
     */
    private static void nameBasedIndexesResolve(ZipFile zip) throws IOException {
        List<String> named = new ArrayList<>();
        named.addAll(indexedClasses(zip, "META-INF/aetherium/content.index"));
        named.addAll(indexedClasses(zip, "META-INF/aetherium/behaviors.index"));
        check("the framework indexes name at least one class", !named.isEmpty());

        List<String> missing = new ArrayList<>();
        for (String binaryName : named) {
            if (zip.getEntry(binaryName.replace('.', '/') + ".class") == null) {
                missing.add(binaryName);
            }
        }
        check("every class named in content/behaviors index is present in the jar", missing.isEmpty(),
                "missing=" + missing);
    }

    private static void entrypointIsPackaged(ZipFile zip) {
        check("the @AetheriumInit entrypoint class is packaged",
                zip.getEntry("org/aetherium/generated/AetheriumGeneratedInit_" + MOD_ID + ".class") != null);
        check("the ServiceLoader manifest for the entrypoint is packaged",
                zip.getEntry("META-INF/services/org.aetherium.core.mod.AetheriumMod") != null);
    }

    /** A Minecraft or loader class inside our jar would break the pure-mod contract. */
    private static void distIsSafe(ZipFile zip) {
        long mc = zip.stream()
                .map(ZipEntry::getName)
                .filter(n -> n.startsWith("net/minecraft/") || n.startsWith("net/neoforged/")
                        || n.startsWith("net/fabricmc/"))
                .count();
        check("no Minecraft/loader classes are embedded", mc == 0, mc + " found");
    }

    private static void bothLoadersAreDescribed(ZipFile zip) {
        check("the jar carries both neoforge.mods.toml and fabric.mod.json",
                zip.getEntry("META-INF/neoforge.mods.toml") != null
                        && zip.getEntry("fabric.mod.json") != null);
    }

    /**
     * The Shield must actually have run on the artifact we ship.
     *
     * <p>The plugin wires the Shield onto the plain {@code jar} task only; the universal jar reads the same
     * classes directory but does not depend on it, so without an explicit dependency this artifact silently
     * ships unprotected. The integrity manifest is the observable proof that it did run.
     */
    private static void shieldWasApplied(ZipFile zip) {
        check("the shield integrity manifest is packaged",
                zip.getEntry("META-INF/aetherium/shield-integrity.txt") != null);
    }

    /**
     * The block model references {@code <modid>:block/<name>}, so the texture must ship with it.
     *
     * <p>the framework stopped substituting a vanilla texture in the generated model. A mod that does
     * not provide the PNG renders as the missing-texture checkerboard — a green build that looks broken to
     * every player, which is exactly the class of defect this verifier exists to catch.
     */
    private static void blockTextureIsPresent(ZipFile zip) throws IOException {
        ZipEntry png = zip.getEntry("assets/" + MOD_ID + "/textures/block/anomaly_core.png");
        check("the anomaly core block texture is packaged", png != null);
        if (png == null) {
            return;
        }
        try (var in = zip.getInputStream(png)) {
            byte[] head = in.readNBytes(24);
            boolean isPng = head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P'
                    && head[2] == 'N' && head[3] == 'G';
            // IHDR width/height are big-endian ints at offsets 16 and 20.
            int w = ((head[16] & 0xFF) << 24) | ((head[17] & 0xFF) << 16)
                    | ((head[18] & 0xFF) << 8) | (head[19] & 0xFF);
            int h = ((head[20] & 0xFF) << 24) | ((head[21] & 0xFF) << 16)
                    | ((head[22] & 0xFF) << 8) | (head[23] & 0xFF);
            check("the block texture is a 16x16 PNG", isPng && w == 16 && h == 16, w + "x" + h);
        }
    }

    /**
     * The folder a player copies into {@code mods/} must be able to run on its own.
     *
     * <p>Two failure modes this catches, both of which produced "the mod is listed but there are no
     * commands and no blocks" in a real session:
     * <ul>
     *   <li>a loader jar that does not carry the framework runtime it links against — its entrypoint
     *       cannot load, so nothing is ever registered; and</li>
     *   <li>the framework present in <em>both</em> jars — two sets of {@code Platform}/{@code NetworkRegistry}
     *       statics, so the mod registers into one and the loader reads the other.</li>
     * </ul>
     */
    private static void gamePackageIsInstallable(Path dir) throws IOException {
        System.out.println();
        System.out.println("Verifying game package " + dir.getFileName());
        if (!Files.isDirectory(dir)) {
            check("the game package directory exists", false, dir.toString());
            return;
        }
        List<Path> jars;
        try (var files = Files.list(dir)) {
            jars = files.filter(f -> f.toString().endsWith(".jar")).sorted().toList();
        }
        check("the package contains exactly three jars (transformer + loader + mod)", jars.size() == 3,
                jars.stream().map(j -> j.getFileName().toString()).toList().toString());
        check("the package ships an INSTALL.md", Files.isRegularFile(dir.resolve("INSTALL.md")));
        if (jars.size() != 3) {
            return;
        }
        check("the package ships the boot-layer transformer",
                jars.stream().anyMatch(j -> j.getFileName().toString().contains("transformer")));

        Path loader = jars.stream().filter(j -> j.getFileName().toString().contains("loader"))
                .findFirst().orElse(null);
        Path mod = jars.stream().filter(j -> j.getFileName().toString().startsWith(MOD_ID))
                .findFirst().orElse(null);
        if (loader == null || mod == null) {
            check("the package contains one loader jar and one mod jar", false);
            return;
        }

        try (ZipFile lz = new ZipFile(loader.toFile()); ZipFile mz = new ZipFile(mod.toFile())) {
            // 1. The loader must carry every framework class it links against, or it cannot even start.
            // Exactly the classes the loader's OWN entrypoint links against (confirmed with javap) plus
            // the PAL/UI it publishes. Modules only the mod uses — aetherium-config, for one — are the
            // mod's own to ship and are checked separately below.
            String[] required = {
                "org/aetherium/core/mod/AetheriumMod", "org/aetherium/core/mod/AetheriumContext",
                "org/aetherium/core/CapabilityTier", "org/aetherium/edge/Platform",
                "org/aetherium/edge/EdgeCommands", "org/aetherium/ui/UiAccess",
                "org/aetherium/shield/ModVerifier", "org/aetherium/loader/AetheriumNeoForgeEntrypoint",
            };
            // The runtime rides as whole nested jars under META-INF/jarjar (NeoForge's own mechanism,
            // deduplicated across mods), so a class counts as carried whether it sits loose in the jar or
            // inside one of those.
            List<String> missing = new ArrayList<>();
            for (String c : required) {
                if (!carries(loader, lz, c)) {
                    missing.add(c);
                }
            }
            check("the loader jar carries the runtime it links against (loose or via jar-in-jar)",
                    missing.isEmpty(), "missing=" + missing);
            check("the loader declares itself a mod", lz.getEntry("META-INF/neoforge.mods.toml") != null);
            check("the loader registers its PlatformBridge and UiAccess services",
                    lz.getEntry("META-INF/services/org.aetherium.edge.PlatformBridge") != null
                            && lz.getEntry("META-INF/services/org.aetherium.ui.UiAccess") != null);

            // 2. The framework may appear in both jars ONLY as nested jars: NeoForge's Jar-in-Jar resolves
            //    those to a single copy across all mods. Loose duplicate classes are what would split the
            //    package and give two sets of statics, so those must not exist in the mod jar.
            long looseFrameworkClasses = mz.stream().map(ZipEntry::getName)
                    .filter(n -> n.startsWith("org/aetherium/") && n.endsWith(".class")
                            && !n.startsWith("org/aetherium/generated/"))
                    .count();
            check("the mod jar ships no loose framework classes (only jar-in-jar, which dedupes)",
                    looseFrameworkClasses == 0, looseFrameworkClasses + " loose classes");

            // Modules the mod uses but the loader does not carry must come from the mod's own nested jars,
            // or nothing resolves them at runtime.
            check("the mod jar supplies the modules the loader does not (e.g. aetherium-config)",
                    carries(mod, mz, "org/aetherium/config/ConfigStore"));

            // 3. Module resolution: the JVM refuses to start when two modules share a package, and it
            //    refuses just as hard when a mod bundles a platform library (ASM lives in the boot layer).
            //    Both mistakes killed a real launch before the game window ever appeared, so both are
            //    asserted here rather than discovered by a player.
            Set<String> loaderPkgs = packagesOf(lz);
            Set<String> modPkgs = packagesOf(mz);
            Set<String> shared = new LinkedHashSet<>(loaderPkgs);
            shared.retainAll(modPkgs);
            check("the two jars share no package (a split package aborts module resolution)",
                    shared.isEmpty(), "shared=" + shared);

            // The same rule across the WHOLE package, counting nested jars. NeoForge turns every
            // META-INF/jarjar entry into its own module, so a package carried loose by one artifact and
            // inside a nested jar of another is exported twice and the JVM refuses to build the module
            // graph — the launch dies before the window appears, with no mod at fault in the message.
            // Identically-named nested jars in two artifacts are fine: Jar-in-Jar resolves those to one.
            noPackageIsExportedTwice(jars);

            List<String> foreign = new ArrayList<>();
            for (ZipFile z : List.of(lz, mz)) {
                z.stream().map(ZipEntry::getName)
                        .filter(n -> n.endsWith(".class"))
                        .filter(n -> n.startsWith("org/objectweb/") || n.startsWith("org/ow2/")
                                || n.startsWith("com/google/") || n.startsWith("org/slf4j/"))
                        .findFirst().ifPresent(foreign::add);
            }
            check("neither jar bundles a platform library (ASM/Guava/SLF4J are provided by the loader layer)",
                    foreign.isEmpty(), "found=" + foreign);

            // 4. The loader must not be claimed by ModLauncher as a transformation service: such a jar is
            //    loaded into the plugin layer and FML never scans it as a mod, so its @Mod entrypoint —
            //    which registers content, commands and the PAL — never runs. A real launch showed exactly
            //    that: the service message printed and no Aetherium entry appeared in the mod list.
            check("the loader is not registered as a ModLauncher service (it must load as a plain mod)",
                    lz.getEntry("META-INF/services/cpw.mods.modlauncher.api.ITransformationService") == null
                            && lz.getEntry("META-INF/services/cpw.mods.modlauncher.serviceapi."
                                    + "ILaunchPluginService") == null);
            check("the loader still provides the PAL and UI services the mod resolves at runtime",
                    lz.getEntry("META-INF/services/org.aetherium.edge.PlatformBridge") != null
                            && lz.getEntry("META-INF/services/org.aetherium.ui.UiAccess") != null);

            // 5. Preview-compiled classes cannot load on a launcher that does not pass --enable-preview.
            //    We cannot fix that from here (the framework is built with preview on), so the invariant
            //    is: if such classes ship, the install instructions must tell the player about the flag.
            long preview = countPreviewClasses(loader);
            if (preview > 0) {
                String install = Files.readString(dir.resolve("INSTALL.md"), StandardCharsets.UTF_8);
                check("INSTALL.md documents --enable-preview (the loader ships " + preview
                                + " preview-compiled classes)",
                        install.contains("--enable-preview"));
            }

            // 6. The mod still contributes everything the loader looks for.
            check("the mod jar still declares its AetheriumMod service",
                    mz.getEntry("META-INF/services/org.aetherium.core.mod.AetheriumMod") != null);
            check("the mod jar still carries its content index",
                    mz.getEntry("META-INF/aetherium/content.index") != null);
            check("the mod jar still carries the generated entrypoint",
                    mz.getEntry("org/aetherium/generated/AetheriumGeneratedInit_" + MOD_ID + ".class") != null);
        }
    }

    // --- helpers --------------------------------------------------------------------------------

    /**
     * Count class files compiled with preview features enabled (minor version {@code 0xFFFF}).
     *
     * <p>Such a class throws {@code UnsupportedClassVersionError} unless the JVM itself was started with
     * {@code --enable-preview}; the {@code Enable-Preview} manifest attribute is metadata and does not
     * enable anything at runtime.
     */
    private static long countPreviewClasses(Path jarPath) throws IOException {
        long n = 0;
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            for (var it = zip.entries().asIterator(); it.hasNext(); ) {
                ZipEntry e = it.next();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                try (var in = zip.getInputStream(e)) {
                    byte[] head = in.readNBytes(8);
                    // magic(4) then minor(2): 0xFFFF marks a preview class file.
                    if (head.length == 8 && (head[4] & 0xFF) == 0xFF && (head[5] & 0xFF) == 0xFF) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    /**
     * No package may be provided by two different modules across the shipped set.
     *
     * <p>Each jar contributes its own loose classes as one module, plus one module per
     * {@code META-INF/jarjar} entry. Two modules exporting the same package is a hard
     * {@code ResolutionException} at boot. The one safe overlap is the *same* nested artifact appearing in
     * two jars — Jar-in-Jar deduplicates those by identity — so nested jars are keyed by file name.
     */
    private static void noPackageIsExportedTwice(List<Path> jars) throws IOException {
        // provider name -> packages it exports
        java.util.Map<String, Set<String>> providers = new java.util.LinkedHashMap<>();
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                Set<String> loose = packagesOf(zip);
                if (!loose.isEmpty()) {
                    providers.put(name + " (loose)", loose);
                }
                for (var it = zip.entries().asIterator(); it.hasNext(); ) {
                    ZipEntry e = it.next();
                    if (!e.getName().startsWith("META-INF/jarjar/") || !e.getName().endsWith(".jar")) {
                        continue;
                    }
                    String nested = e.getName().substring(e.getName().lastIndexOf('/') + 1);
                    Set<String> pkgs = new LinkedHashSet<>();
                    try (var in = new java.util.zip.ZipInputStream(zip.getInputStream(e))) {
                        for (ZipEntry inner; (inner = in.getNextEntry()) != null; ) {
                            if (inner.getName().endsWith(".class") && inner.getName().contains("/")) {
                                pkgs.add(inner.getName().substring(0, inner.getName().lastIndexOf('/')));
                            }
                        }
                    }
                    // Keyed by nested file name: the same artifact in two jars is deduplicated by JarJar.
                    providers.merge(nested, pkgs, (a, b) -> a);
                }
            }
        }
        List<String> clashes = new ArrayList<>();
        List<String> names = new ArrayList<>(providers.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                Set<String> overlap = new LinkedHashSet<>(providers.get(names.get(i)));
                overlap.retainAll(providers.get(names.get(j)));
                if (!overlap.isEmpty()) {
                    clashes.add(names.get(i) + " ∩ " + names.get(j) + " = " + overlap);
                }
            }
        }
        check("no package is exported by two modules across the whole package (incl. nested jars)",
                clashes.isEmpty(), String.join("; ", clashes));
    }

    /** Does {@code jar} carry {@code binaryPath}, either loose or inside one of its nested jars? */
    private static boolean carries(Path jarPath, ZipFile zip, String binaryPath) throws IOException {
        if (zip.getEntry(binaryPath + ".class") != null) {
            return true;
        }
        for (var it = zip.entries().asIterator(); it.hasNext(); ) {
            ZipEntry e = it.next();
            if (!e.getName().startsWith("META-INF/jarjar/") || !e.getName().endsWith(".jar")) {
                continue;
            }
            try (var nested = new java.util.zip.ZipInputStream(zip.getInputStream(e))) {
                for (ZipEntry inner; (inner = nested.getNextEntry()) != null; ) {
                    if (inner.getName().equals(binaryPath + ".class")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Every package that contains at least one class in {@code zip}. */
    private static Set<String> packagesOf(ZipFile zip) {
        Set<String> packages = new LinkedHashSet<>();
        zip.stream().map(ZipEntry::getName)
                .filter(n -> n.endsWith(".class"))
                .map(n -> n.contains("/") ? n.substring(0, n.lastIndexOf('/')) : "")
                .forEach(packages::add);
        return packages;
    }

    /** Class names referenced by a framework index line (`KIND|modid|name|CLASS|...`). */
    private static List<String> indexedClasses(ZipFile zip, String path) throws IOException {
        List<String> names = new ArrayList<>();
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            return names;
        }
        for (String line : read(zip, entry).split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            for (String field : trimmed.split("\\|")) {
                // A class reference is the only dotted field that looks like a package path.
                if (field.contains(".") && field.matches("[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*)+")) {
                    names.add(field);
                }
            }
        }
        return names;
    }

    /** Minimal flat-JSON key reader — a lang file is a one-level string map, so no parser is warranted. */
    private static Set<String> langKeys(ZipFile zip, String path) throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            return keys;
        }
        Matcher m = Pattern.compile("\"([^\"]+)\"\\s*:").matcher(read(zip, entry));
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    private static String read(ZipFile zip, ZipEntry entry) throws IOException {
        try (var in = zip.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void check(String what, boolean ok) {
        check(what, ok, "");
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            System.out.println("  ok   " + what);
        } else {
            failures++;
            System.out.println("  FAIL " + what + (detail.isEmpty() ? "" : " — " + detail));
        }
    }
}
