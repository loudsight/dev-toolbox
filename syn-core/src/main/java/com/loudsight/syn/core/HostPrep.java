package com.loudsight.syn.core;

/**
 * What the deploy host's checkout needs before anything is built from it, as one bash fragment shared by {@code syn
 * deploy-run} and {@code syn --on-remote --sync}: an exclusive lock, a refusal to pull over hand edits, then the pull
 * and the submodule update. Run it inside the checkout (the caller has already {@code cd}'d), under {@code set -e}.
 */
public final class HostPrep {

    private static final String SYNC = """
  # Exclusive access to this shared checkout for the whole remote run: a second concurrent
  # run pulling here mid-build would let its pull change source a build already in
  # progress is compiling from. flock's lock lives on this fd, so it releases automatically
  # whenever this script exits, on any path, with no explicit unlock needed.
  exec 9>/opt/.deploy-run.lock
  if ! flock -n 9; then
    echo '❌ Another deploy-run or syn --on-remote --sync is already using this checkout - re-run once it finishes.'
    exit 1
  fi

  echo '📥 Pulling latest code...'
  # A tracked file modified directly on the deploy host - typically a hotfix
  # applied here during an incident, later landed on master through a separate
  # commit but never reverted here - makes 'git pull' abort with a raw "Your
  # local changes would be overwritten by merge / Aborting" and no file list.
  # Name the files and the remedy before the pull's own opaque abort.
  # deploy-check-preflight cannot cover this: it runs AFTER the pull, by design, so
  # it validates the pulled revision rather than this working copy.
  host_dirty=$(git status --porcelain --untracked-files=no)
  if [ -n "$host_dirty" ]; then
    echo '❌ Deploy host working tree has uncommitted tracked changes - refusing to pull:'
    echo "$host_dirty" | sed 's/^/       /'
    echo '   On the deploy host: commit them, or "git checkout -- <file>" if they are'
    echo '   already on master through another commit, then re-run.'
    exit 1
  fi
  # --no-recurse-submodules: a plain pull also fetches every submodule pointer on the way, by hash. A public
  # submodule whose history was rewritten has orphaned pointers in the superproject's past, the remote refuses each
  # ("not our ref"), and the deploy dies here, before it starts (2026-10-11). Only the pointer the pull lands on is
  # needed, and 'git submodule update' below fetches exactly that.
  git pull --no-recurse-submodules origin master -q
  git submodule sync --recursive 2>/dev/null || true
  git submodule update --init --recursive
""";

    private HostPrep() {
    }

    /**
     * Lock, refuse a dirty tree, pull, update submodules. The lock is on fd 9, so it is released whenever the script
     * exits, on any path, and every child the script starts afterwards inherits it - the whole run is exclusive.
     * A method, not a public constant: a constant is copied into every class that uses it.
     */
    public static String sync() {
        return SYNC;
    }
}
