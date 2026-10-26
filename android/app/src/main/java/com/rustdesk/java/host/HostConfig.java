package com.rustdesk.java.host;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

/**
 * The few settings the host side needs: the id it registers under, the password a viewer has to
 * answer, and the rendezvous server that brokers the connection.
 */
public class HostConfig {

    private static final String PREFS = "rustdesk_java_host";

    private static final String KEY_ID = "id";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_SERVER = "rendezvous_server";

    /** Mirrors the viewer default; the viewer keeps its own private copy of the same host. */
    public static final String DEFAULT_RENDEZVOUS = "rs-ny.rustdesk.com";

    private final SharedPreferences prefs;

    public HostConfig(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The id is generated on first use, the same way {@code DeviceIdentity} does it. */
    public String getId() {
        String id = prefs.getString(KEY_ID, null);
        if (id == null || id.isEmpty()) {
            id = generateId();
            prefs.edit().putString(KEY_ID, id).apply();
        }
        return id;
    }

    public void setId(String id) {
        prefs.edit().putString(KEY_ID, id == null ? "" : id).apply();
    }

    public String getPassword() {
        return prefs.getString(KEY_PASSWORD, "");
    }

    public void setPassword(String password) {
        prefs.edit().putString(KEY_PASSWORD, password == null ? "" : password).apply();
    }

    public String getRendezvousServer() {
        String server = prefs.getString(KEY_SERVER, null);
        return server == null || server.isEmpty() ? DEFAULT_RENDEZVOUS : server;
    }

    public void setRendezvousServer(String server) {
        prefs.edit().putString(KEY_SERVER, server == null ? "" : server).apply();
    }

    /** RustDesk ids are nine decimal digits. */
    private static String generateId() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 9; i++) {
            sb.append(random.nextInt(10));
        }
        return sb.toString();
    }
}
