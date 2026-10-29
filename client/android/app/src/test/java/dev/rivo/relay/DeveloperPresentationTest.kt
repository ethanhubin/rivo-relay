package dev.rivo.relay

import dev.rivo.relay.ui.chat.*
import org.junit.Assert.*
import org.junit.Test

class DeveloperPresentationTest {
    @Test fun reviewDirectiveKeepsQuotedBracesAndFileLocation() {
        val comment = parseReviewComment("""::code-comment{title="[P1] Escaped \"quote\"" body="Preserve {data} and \\paths." file="/project/a b.kt" start=12 end=14 priority=1}""")
        assertNotNull(comment)
        assertEquals("[P1] Escaped \"quote\"", comment!!.title)
        assertEquals("Preserve {data} and \\paths.", comment.body)
        assertEquals("/project/a b.kt", comment.file)
        assertEquals(12, comment.start)
        assertEquals(14, comment.end)
        assertEquals(1, comment.priority)
    }

    @Test fun invalidLocationAndUnclosedQuotesRemainText() {
        assertNull(parseReviewComment("""::code-comment{title="Issue" body="Details" file="x" start=0}"""))
        assertNull(parseReviewComment("""::code-comment{title="Issue" body="Details" file="x" start=20 end=10}"""))
        assertNull(parseReviewComment("""::code-comment{title="Issue" body="Details file="x"}"""))
    }

    @Test fun exampleDirectivesInsideCodeFenceAreNotFindings() {
        val directive = """::code-comment{title="Issue" body="Details" file="/project/a.kt"}"""
        val parts = reviewMessageParts("Before\n```text\n$directive\n```\n$directive\nAfter")
        assertEquals(1, parts.filterIsInstance<ReviewMessagePart.Finding>().size)
        assertTrue(parts.filterIsInstance<ReviewMessagePart.Text>().first().value.contains(directive))
    }

    @Test fun diffUsesSourceLinePositionsAndResetsForEachHunk() {
        val lines = listOf("diff --git a/a.kt b/a.kt", "--- a/a.kt", "+++ b/a.kt", "@@ -10,2 +10,2 @@", " old", "-removed", "+added", "\\ No newline at end of file", "@@ -90 +100 @@", " context")
        val numbers = diffLineNumbers(lines)
        assertEquals("  10   10", numbers[4])
        assertEquals("  11      ", numbers[5])
        assertEquals("       11", numbers[6])
        assertTrue(numbers[7].isBlank())
        assertEquals("  90  100", numbers[9])
    }

    @Test fun fileFilterDoesNotMixFilesWithSimilarNames() {
        val patch = "diff --git a/src/a.kt b/src/a.kt\n--- a/src/a.kt\n+++ b/src/a.kt\n@@ -1 +1 @@\n-old\n+one\n" +
            "diff --git a/test/a.kt b/test/a.kt\n--- a/test/a.kt\n+++ b/test/a.kt\n@@ -1 +1 @@\n-old\n+two\n"
        val filtered = diffForFile(patch, "test/a.kt")
        assertTrue(filtered.contains("+two"))
        assertFalse(filtered.contains("+one"))
        assertEquals("", diffForFile(patch, "absent.kt"))
        assertTrue(diffForFile("diff --git a/new file.txt b/new file.txt\n+++ b/new file.txt\t\n@@ -0,0 +1 @@\n+text\n", "new file.txt").contains("+text"))
    }
}
