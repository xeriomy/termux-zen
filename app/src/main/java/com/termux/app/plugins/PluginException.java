package com.termux.app.plugins;

/** A validation, compatibility, trust, or installation failure. Messages are safe to show to the user. */
public class PluginException extends Exception {

    public PluginException(String message) {
        super(message);
    }

    public PluginException(String message, Throwable cause) {
        super(message, cause);
    }

}
