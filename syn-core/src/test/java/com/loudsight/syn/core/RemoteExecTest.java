package com.loudsight.syn.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.loudsight.scriptkit.Cmd;
import java.util.List;
import org.junit.jupiter.api.Test;

class RemoteExecTest {

    @Test
    void theScriptSourcesTheHostToolchainEntersTheCheckoutAndRunsSynWithTheSameArguments() {
        String script = RemoteExec.script("/opt/code/synergistically", List.of("build-run-modules", "--modules", "x", "--dry-run"));

        assertEquals("source /opt/myEnv.sh && export WORK_ROOT=/opt && cd /opt/code/synergistically"
                + " && scripts/syn build-run-modules --modules x --dry-run", script);
    }

    /** The host's shell sees the script after ssh's own word splitting, so quote twice and let a real bash undo both. */
    @Test
    void argumentsWithShellMetacharactersSurviveBothShellsIntact() {
        List<String> tricky = List.of("*backend*; rm -rf /", "it's", "a b", "$HOME `id`", "--x=\"y\"");
        String script = RemoteExec.script("/srv/my checkout", tricky);

        String evaluated = Cmd.of("bash", "-c", "printf '%s\\n' " + RemoteExec.sshCommand("h", script).get(10)).capture().orThrow();

        assertEquals(script, evaluated.strip(), "the outer quoting must hand the host's bash the script unchanged");
        String inner = Cmd.of("bash", "-c", "set -- " + script.substring(script.indexOf("scripts/syn ") + "scripts/syn ".length())
                + "; printf '%s\\n' \"$@\"").capture().orThrow();
        assertEquals(String.join("\n", tricky), inner.strip());
    }

    @Test
    void sshRunsTheScriptAsOneQuotedArgumentOfBashCWithTimeouts() {
        List<String> ssh = RemoteExec.sshCommand("deploy@host.invalid", "cd /x && scripts/syn a");

        assertEquals(List.of("ssh", "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3",
                "deploy@host.invalid", "bash", "-c"), ssh.subList(0, 10));
        assertEquals(11, ssh.size());
        assertEquals("'cd /x && scripts/syn a'", ssh.get(10));
    }

    @Test
    void aSyncScriptTakesTheLockAndPullsBeforeItRunsSynAndIsValidBash() {
        String script = RemoteExec.script("/opt/code/synergistically", List.of("build-run-modules", "--dry-run"), true);

        assertTrue(script.contains(HostPrep.sync()), script);
        int env = script.indexOf("source /opt/myEnv.sh");
        int strict = script.indexOf("set -e");
        int enter = script.indexOf("cd /opt/code/synergistically");
        int lock = script.indexOf("flock -n 9");
        int pull = script.indexOf("git pull --no-recurse-submodules origin master");
        int syn = script.indexOf("scripts/syn build-run-modules --dry-run");
        assertTrue(env >= 0 && env < strict && strict < enter && enter < lock && lock < pull && pull < syn, script);
        assertTrue(script.endsWith("scripts/syn build-run-modules --dry-run\n"), script);
        assertTrue(Cmd.of("bash", "-n").stdin(script).capture().ok(), "bash -n rejected the sync script");
    }

    @Test
    void aPlainScriptHasNoLockAndNoPull() {
        String script = RemoteExec.script("/x", List.of("a"));

        assertTrue(!script.contains("flock") && !script.contains("git pull"), script);
    }

    @Test
    void noRunForwardsTheAgentOrNeedsATerminal() {
        List<String> ssh = RemoteExec.sshCommand("h", RemoteExec.script("/x", List.of("a"), true));

        assertTrue(!ssh.contains("-A") && !ssh.contains("-t") && !ssh.contains("-tt") && !ssh.contains("-ic"), ssh.toString());
    }
}
