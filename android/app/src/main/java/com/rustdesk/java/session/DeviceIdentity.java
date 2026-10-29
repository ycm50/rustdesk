package com.rustdesk.java.session;

import android.content.Context;
import android.content.SharedPreferences;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator;
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters;
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

import java.security.SecureRandom;

/**
 * Our own device identity: the ed25519 key pair the rendezvous server signs and the id we register
 * under. Both are persisted so the server keeps recognising the same device.
 */
public class DeviceIdentity {

    private static final String PREFS = "rustdesk_java_identity";
    private static final String KEY_ID = "id";
    private static final String KEY_SEED = "ed25519_seed";
    private static final String KEY_UUID = "register_uuid";

    private final String id;
    private final byte[] signPublicKey;
    private final byte[] registerUuid;
    private final Ed25519PrivateKeyParameters signPrivateKey;

    private DeviceIdentity(String id, Ed25519PrivateKeyParameters priv, Ed25519PublicKeyParameters pub,
                        byte[] registerUuid) {
        this.id = id;
        this.signPrivateKey = priv;
        this.signPublicKey = pub.getEncoded();
        this.registerUuid = registerUuid.clone();
    }

    public byte[] getRegisterUuid() {
        return registerUuid.clone();
    }

    public String getId() {
        return id;
    }

    public byte[] getSignPublicKey() {
        return signPublicKey.clone();
    }

    public Ed25519PrivateKeyParameters getSignPrivateKey() {
        return signPrivateKey;
    }

    public static DeviceIdentity load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_ID, null);
        String seedHex = prefs.getString(KEY_SEED, null);
        String uuidHex = prefs.getString(KEY_UUID, null);

        Ed25519PrivateKeyParameters priv;
        if (seedHex == null || seedHex.isEmpty()) {
            byte[] seed = new byte[32];
            new SecureRandom().nextBytes(seed);
            priv = new Ed25519PrivateKeyParameters(seed, 0);
            prefs.edit().putString(KEY_SEED, toHex(seed)).apply();
        } else {
            priv = new Ed25519PrivateKeyParameters(fromHex(seedHex), 0);
        }

        if (id == null || id.isEmpty()) {
            id = generateId();
            prefs.edit().putString(KEY_ID, id).apply();
        }

        // The server remembers the uuid it first saw for an id and answers RegisterPk with
        // UUID_MISMATCH when a later registration presents a different one, so it has to be
        // generated once and reused for the lifetime of the install.
        byte[] uuid;
        if (uuidHex == null || uuidHex.isEmpty()) {
            uuid = new byte[16];
            new SecureRandom().nextBytes(uuid);
            prefs.edit().putString(KEY_UUID, toHex(uuid)).apply();
        } else {
            uuid = fromHex(uuidHex);
        }

        return new DeviceIdentity(id, priv, priv.generatePublicKey(), uuid);
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

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}

