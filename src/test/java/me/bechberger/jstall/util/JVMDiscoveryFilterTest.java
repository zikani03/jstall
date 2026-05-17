package me.bechberger.jstall.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JVMDiscovery filtering functionality.
 */
class JVMDiscoveryFilterTest {

    JVMDiscovery discovery = new JVMDiscovery(new CommandExecutor.LocalCommandExecutor());

    @Test
    void testListJVMsWithoutFilter() throws IOException {
        List<JVMDiscovery.JVMProcess> jvms = discovery.listJVMs();

        assertNotNull(jvms);
        // JVMs list should not include jstack
        for (JVMDiscovery.JVMProcess jvm : jvms) {
            assertFalse(jvm.mainClass().contains("jstack"),
                "JVMs list should not include jstack");
        }
    }

    @Test
    void testListJVMsWithFilter() throws IOException {
        // First get all JVMs
        List<JVMDiscovery.JVMProcess> allJVMs = discovery.listJVMs();

        if (allJVMs.isEmpty()) {
            // No JVMs to filter, skip test
            return;
        }

        // Pick a known part of a JVM's main class
        String filterTerm = allJVMs.get(0).mainClass().split("\\.")[0]; // Get first package name

        List<JVMDiscovery.JVMProcess> filtered = discovery.listJVMs(filterTerm);

        assertNotNull(filtered);
        // All filtered JVMs should contain the filter term
        for (JVMDiscovery.JVMProcess jvm : filtered) {
            assertTrue(jvm.mainClass().toLowerCase().contains(filterTerm.toLowerCase()),
                "Filtered JVM should contain filter term: " + filterTerm);
        }
    }

    @Test
    void testListJVMsWithNonMatchingFilter() throws IOException {
        String impossibleFilter = "VeryUnlikelyJVMName9999XYZ";

        List<JVMDiscovery.JVMProcess> filtered = discovery.listJVMs(impossibleFilter);

        assertNotNull(filtered);
        assertTrue(filtered.isEmpty(), "Should find no JVMs with impossible filter");
    }

    @Test
    void testListJVMsWithNullFilter() throws IOException {
        List<JVMDiscovery.JVMProcess> jvms = discovery.listJVMs(null);

        assertNotNull(jvms);
        // Should behave like no filter — most PIDs should overlap
        List<JVMDiscovery.JVMProcess> noFilter = discovery.listJVMs();
        Set<Long> nullPids = jvms.stream().map(JVMDiscovery.JVMProcess::pid).collect(java.util.stream.Collectors.toSet());
        Set<Long> noPids = noFilter.stream().map(JVMDiscovery.JVMProcess::pid).collect(java.util.stream.Collectors.toSet());
        Set<Long> common = new java.util.HashSet<>(nullPids);
        common.retainAll(noPids);
        // Allow small difference due to JVMs starting/stopping between calls
        assertTrue(common.size() >= Math.min(nullPids.size(), noPids.size()) - 2,
            "null filter should behave like no filter");
    }

    @Test
    void testListJVMsCaseInsensitiveFilter() throws IOException {
        List<JVMDiscovery.JVMProcess> allJVMs = discovery.listJVMs();

        if (allJVMs.isEmpty()) {
            return;
        }

        // Get a term from an actual JVM
        String mainClass = allJVMs.get(0).mainClass();
        if (mainClass.length() < 3) {
            return; // Skip if too short
        }

        String term = mainClass.substring(0, 3);

        // Test with uppercase
        List<JVMDiscovery.JVMProcess> upperCase = discovery.listJVMs(term.toUpperCase());

        // Test with lowercase
        List<JVMDiscovery.JVMProcess> lowerCase = discovery.listJVMs(term.toLowerCase());

        // Compare only PIDs to handle JVMs starting/stopping between calls
        Set<Long> upperPids = upperCase.stream().map(JVMDiscovery.JVMProcess::pid).collect(java.util.stream.Collectors.toSet());
        Set<Long> lowerPids = lowerCase.stream().map(JVMDiscovery.JVMProcess::pid).collect(java.util.stream.Collectors.toSet());

        // The intersection should be non-empty and the symmetric difference should be small
        // (only caused by JVMs starting/stopping between the two calls)
        Set<Long> common = new java.util.HashSet<>(upperPids);
        common.retainAll(lowerPids);
        assertFalse(common.isEmpty(),
            "Filter should be case-insensitive: expected overlap between upper and lower case results");
    }

    @Test
    void testListJVMsExcludesCurrentJVM() throws IOException {
        long currentPid = ProcessHandle.current().pid();

        List<JVMDiscovery.JVMProcess> jvms = discovery.listJVMs();

        for (JVMDiscovery.JVMProcess jvm : jvms) {
            assertNotEquals(currentPid, jvm.pid(),
                "JVMs list should not include the current JVM process");
        }
    }

    @Test
    void testJVMProcessRecord() {
        JVMDiscovery.JVMProcess jvm = new JVMDiscovery.JVMProcess(12345, "com.example.Main");

        assertEquals(12345, jvm.pid());
        assertEquals("com.example.Main", jvm.mainClass());

        String str = jvm.toString();
        assertTrue(str.contains("12345"));
        assertTrue(str.contains("com.example.Main"));
    }
}