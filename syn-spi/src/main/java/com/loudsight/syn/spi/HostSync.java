package com.loudsight.syn.spi;

/**
 * How the deploy host is set up for a remote run ({@code syn --on-remote}). A plugin may supply one, registered in
 * {@code META-INF/services/com.loudsight.syn.spi.HostSync} with a public no-argument constructor; without one the head
 * uses its built-in default. Both methods return bash text run on the host.
 */
public interface HostSync {

    /**
     * What runs on the host before it enters the checkout, for a run that pulls and for one that does not: typically
     * the toolchain environment. One command per line; a run that does not pull joins the lines with {@code &&}.
     */
    String envPrelude();

    /**
     * What a run that pulls does inside the checkout before the command starts (lock, refusal over hand edits, pull,
     * submodule update). It runs under {@code set -e}, after the {@code cd}; each line ends with a newline.
     */
    String syncFragment();
}
