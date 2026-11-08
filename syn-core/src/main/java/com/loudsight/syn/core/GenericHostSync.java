package com.loudsight.syn.core;

import com.loudsight.syn.spi.HostSync;

/**
 * The host setup used when no plugin supplies a {@link HostSync}: no environment prelude, and a plain pull of the
 * checkout's current branch followed by a submodule sync and update.
 */
public final class GenericHostSync implements HostSync {

    @Override
    public String envPrelude() {
        return "";
    }

    @Override
    public String syncFragment() {
        return "git pull -q\ngit submodule sync -q\ngit submodule update --init --recursive -q\n";
    }
}
