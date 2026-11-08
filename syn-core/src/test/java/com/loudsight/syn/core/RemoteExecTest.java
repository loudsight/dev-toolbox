package com.loudsight.syn.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.scriptkit.Cmd;
import com.loudsight.syn.spi.HostSync;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteExecTest {

    @Test
    void theLauncherIsGenericUnlessTheRepoConfiguresIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws java.io.IOException {
        assertEquals("syn", RemoteExec.launcher(root));
        java.nio.file.Files.createDirectory(root.resolve(".syn"));
        java.nio.file.Files.writeString(root.resolve(".syn/config.properties"), "remote.launcher=scripts/syn\n");

        assertEquals("scripts/syn", RemoteExec.launcher(root));
        assertEquals("cd /x && scripts/syn a", RemoteExec.script("/x", List.of("a"), false, RemoteExec.DEFAULT_HOST_SYNC, "scripts/syn"));
    }

    @Test
    void theScriptEntersTheCheckoutAndRunsSynWithTheSameArguments() {
        String script = RemoteExec.script("/srv/checkout", List.of("build-run-modules", "--modules", "x", "--dry-run"));

        assertEquals("cd /srv/checkout && syn build-run-modules --modules x --dry-run", script);
    }

    /** The host's shell sees the script after ssh's own word splitting, so quote twice and let a real bash undo both. */
    @Test
    void argumentsWithShellMetacharactersSurviveBothShellsIntact() {
        List<String> tricky = List.of("*backend*; rm -rf /", "it's", "a b", "$HOME `id`", "--x=\"y\"");
        String script = RemoteExec.script("/srv/my checkout", tricky);

        String evaluated = Cmd.of("bash", "-c", "printf '%s\\n' " + RemoteExec.sshCommand("h", script).get(10)).capture().orThrow();

        assertEquals(script, evaluated.strip(), "the outer quoting must hand the host's bash the script unchanged");
        String inner = Cmd.of("bash", "-c", "set -- " + script.substring(script.indexOf("syn ") + "syn ".length())
                + "; printf '%s\\n' \"$@\"").capture().orThrow();
        assertEquals(String.join("\n", tricky), inner.strip());
    }

    @Test
    void sshRunsTheScriptAsOneQuotedArgumentOfBashCWithTimeouts() {
        List<String> ssh = RemoteExec.sshCommand("deploy@host.invalid", "cd /x && syn a");

        assertEquals(List.of("ssh", "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3",
                "deploy@host.invalid", "bash", "-c"), ssh.subList(0, 10));
        assertEquals(11, ssh.size());
        assertEquals("'cd /x && syn a'", ssh.get(10));
    }

    @Test
    void aSyncScriptPullsAndUpdatesSubmodulesBeforeItRunsSynAndIsValidBash() {
        String script = RemoteExec.script("/srv/checkout", List.of("build-run-modules", "--dry-run"), true);

        assertTrue(script.contains(new GenericHostSync().syncFragment()), script);
        int strict = script.indexOf("set -e");
        int enter = script.indexOf("cd /srv/checkout");
        int pull = script.indexOf("git pull -q");
        int submodules = script.indexOf("git submodule update");
        int syn = script.indexOf("syn build-run-modules --dry-run");
        assertTrue(strict == 0 && strict < enter && enter < pull && pull < submodules && submodules < syn, script);
        assertTrue(script.endsWith("syn build-run-modules --dry-run\n"), script);
        assertTrue(Cmd.of("bash", "-n").stdin(script).capture().ok(), "bash -n rejected the sync script");
    }

    @Test
    void aPlainScriptHasNoLockAndNoPull() {
        String script = RemoteExec.script("/x", List.of("a"));

        assertTrue(!script.contains("flock") && !script.contains("git pull"), script);
    }

    private static final HostSync CUSTOM = new HostSync() {
        @Override
        public String envPrelude() {
            return "source /etc/custom-env.sh\nexport WORK_ROOT=/srv";
        }

        @Override
        public String syncFragment() {
            return "echo custom-sync\n";
        }
    };

    @Test
    void aProvidedHostSyncReplacesThePreludeAndTheSyncFragment() {
        String plain = RemoteExec.script("/x", List.of("a"), false, CUSTOM);
        String sync = RemoteExec.script("/x", List.of("a"), true, CUSTOM);

        assertEquals("source /etc/custom-env.sh && export WORK_ROOT=/srv && cd /x && syn a", plain);
        assertEquals("source /etc/custom-env.sh\nexport WORK_ROOT=/srv\nset -e\ncd /x\necho custom-sync\nsyn a\n", sync);
    }

    @Test
    void aRequestWithoutAHostSyncFallsBackToTheDefault() {
        assertSame(RemoteExec.DEFAULT_HOST_SYNC, new RemoteExec.Request("h", "/x", List.of("a"), true, false).hostSync());
        assertSame(CUSTOM, new RemoteExec.Request("h", "/x", List.of("a"), true, false, CUSTOM).hostSync());
    }

    @Test
    void noRunForwardsTheAgentOrNeedsATerminal() {
        List<String> ssh = RemoteExec.sshCommand("h", RemoteExec.script("/x", List.of("a"), true));

        assertTrue(!ssh.contains("-A") && !ssh.contains("-t") && !ssh.contains("-tt") && !ssh.contains("-ic"), ssh.toString());
    }
}
