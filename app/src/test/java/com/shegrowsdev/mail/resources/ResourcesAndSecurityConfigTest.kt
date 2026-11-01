package com.shegrowsdev.mail.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks that read the project files themselves (the Gradle test working directory is the app module):
 * Arabic and English must define the same texts with the same placeholders, and the security configuration
 * must stay as reviewed.
 */
class ResourcesAndSecurityConfigTest {
    private val main = File("src/main")

    private fun strings(path: String): Map<String, String> {
        val xml = File(main, path).readText()
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholders(s: String) = Regex("""%(\d+\$)?[sd]""").findAll(s).map { it.value }.sorted().toList()

    @Test fun arabicAndEnglishDefineTheSameTexts() {
        val en = strings("res/values/strings.xml")
        val ar = strings("res/values-ar/strings.xml")
        assertTrue("English strings were not found (wrong working directory?)", en.size > 100)
        assertEquals("missing in Arabic", emptySet<String>(), en.keys - ar.keys)
        assertEquals("missing in English", emptySet<String>(), ar.keys - en.keys)
    }

    @Test fun placeholdersMatchBetweenLanguages() {
        val en = strings("res/values/strings.xml")
        val ar = strings("res/values-ar/strings.xml")
        for ((k, v) in en) assertEquals("placeholders of $k", placeholders(v), placeholders(ar.getValue(k)))
    }

    @Test fun noTextIsEmptyOrLeftAsAPlaceholderWord() {
        for (file in listOf("res/values/strings.xml", "res/values-ar/strings.xml")) {
            for ((k, v) in strings(file)) {
                assertTrue("$file $k is empty", v.isNotBlank())
                assertFalse("$file $k looks unfinished", Regex("""\b(TODO|TBD|lorem)\b""", RegexOption.IGNORE_CASE).containsMatchIn(v))
            }
        }
    }

    @Test fun arabicTextsActuallyContainArabic() {
        val ar = strings("res/values-ar/strings.xml")
        val arabic = Regex("[\\u0600-\\u06FF]")
        val notArabic = ar.filter { (_, v) -> !arabic.containsMatchIn(v) }.keys
        // Only brand names, units and similar may stay Latin.
        assertTrue("Arabic file has untranslated texts: $notArabic", notArabic.size <= 5)
    }

    @Test fun trafficIsNeverClearText() {
        val cfg = File(main, "res/xml/network_security_config.xml").readText()
        assertTrue(cfg.contains("cleartextTrafficPermitted=\"false\""))
        assertFalse("no user-installed trust anchors", cfg.contains("src=\"user\""))
        assertFalse(cfg.contains("cleartextTrafficPermitted=\"true\""))
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertFalse(manifest.contains("usesCleartextTraffic=\"true\""))
        assertTrue(manifest.contains("networkSecurityConfig"))
    }

    @Test fun appDataIsExcludedFromAndroidBackups() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        val rules = File(main, "res/xml/data_extraction_rules.xml").readText()
        for (d in listOf("file", "database", "sharedpref")) assertTrue(d, rules.contains("exclude domain=\"$d\""))
    }

    @Test fun sourceNeverLogsOrPrintsAndNeverEnablesMailDebug() {
        val offenders = File(main, "java").walkTopDown().filter { it.extension == "kt" }.filter {
            val t = it.readText()
            Regex("""\bLog\.[dviwe]\(""").containsMatchIn(t) || t.contains("println(") ||
                t.contains("printStackTrace") || Regex("""setDebug\(\s*true""").containsMatchIn(t) ||
                t.contains("\"mail.debug\"")
        }.map { it.name }.toList()
        assertEquals("these files log or enable debug output: $offenders", emptyList<String>(), offenders)
    }

    @Test fun noCredentialsOrKeysAreCommittedToTheProject() {
        val root = File("..") // repository root
        val suspicious = Regex("""(?i)(password|passwd|secret|api[_-]?key)\s*[:=]\s*["'][^"'\s${'$'}{][^"']{5,}["']""")
        val hits = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" && it.name != "test" }
            .filter { it.isFile && it.extension in setOf("kt", "kts", "xml", "yml", "yaml", "properties", "md") }
            .filter { suspicious.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path }.toList()
        assertEquals("possible secrets in: $hits", emptyList<String>(), hits)
        val keystores = root.walkTopDown().onEnter { it.name != ".git" && it.name != "build" }
            .filter { it.isFile && it.extension in setOf("jks", "keystore", "p12", "pfx") }.toList()
        assertTrue("keystore files must never be committed: $keystores", keystores.isEmpty())
    }
}
