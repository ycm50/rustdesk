package com.rustdesk.java.session;

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import hbb.Rendezvous.IdPk;

/**
 * Verifies the identity blob a peer sends in {@code SignedId}.
 *
 * <p>The rendezvous server signs a protobuf {@link IdPk} (id, curve25519 public key, optional DTLS
 * fingerprint, advertised key exchange version) with the deployment's ed25519 key; the peer relays
 * the resulting signature and nothing else. See src/common.rs:decode_id_pk_dtls.
 */
public final class PeerIdentity {

    /** Default public RustDesk server key, from hbb_common/src/config.rs:RS_PUB_KEY. */
    public static final String DEFAULT_RS_PUB_KEY = "OeVuKk5nlHiXp+APNn0Y3pC1Iwpwn44JGqrQCsWqmBw=";

    public final String id;
    /**
     * The 32-byte key carried inside the blob. Its role depends on the signer:
     *
     * <ul>
     *   <li>blob signed by the rendezvous server ({@code PunchHoleResponse.pk}) — this is the
     *       peer's <em>ed25519 signing</em> key, the one that verifies the peer's own SignedId;
     *   <li>blob signed by the peer ({@code SignedId.id}) — this is the peer's <em>curve25519
     *       box</em> key, the one the stream key is sealed to.
     * </ul>
     */
    public final byte[] publicKey;
    public final int kxVersion;
    public final String dtlsFingerprint;

    private PeerIdentity(String id, byte[] publicKey, int kxVersion, String dtlsFingerprint) {
        this.id = id;
        this.publicKey = publicKey;
        this.kxVersion = kxVersion;
        this.dtlsFingerprint = dtlsFingerprint;
    }

    public static byte[] decodeRsPublicKey(String base64) {
        byte[] raw = java.util.Base64.getDecoder().decode(base64);
        if (raw.length != 32) {
            throw new IllegalArgumentException("rendezvous public key must be 32 bytes");
        }
        return raw;
    }

    /**
     * Verifies an IdPk blob against {@code signerKey} and returns its contents.
     *
     * @param signed    ed25519 signature(64) followed by the serialized IdPk
     * @param signerKey the 32-byte ed25519 key that signed the blob
     */
    public static PeerIdentity verify(byte[] signed, byte[] signerKey) {
        if (signed == null || signed.length < 64) {
            throw new IllegalArgumentException("signed identity too short");
        }
        // libsodium's verify() treats the buffer as signature(64) || message, and BouncyCastle
        // needs the two parts separately.
        byte[] payload;
        try {
            payload = com.rustdesk.java.crypto.Ed25519.verify(signed, signerKey);
            IdPk idPk = IdPk.parseFrom(payload);
            if (idPk.getPk().size() != 32) {
                throw new IllegalArgumentException("peer public key must be 32 bytes");
            }
            return new PeerIdentity(idPk.getId(), idPk.getPk().toByteArray(),
                    idPk.getKxVersion(), idPk.getDtlsFingerprint());
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed or unverified identity blob", e);
        }
    }

    /**
     * Reads an IdPk blob without checking its signature.
     *
     * <p>Only for a direct IP connection: there is no rendezvous server, so nobody can hand us the
     * peer's ed25519 key and there is nothing to verify against. That trades identity
     * authentication for working with no server at all, which is what the direct mode is for. Do
     * not use it on the rendezvous path.
     */
    public static PeerIdentity parseUnverified(byte[] signed) {
        if (signed == null || signed.length <= 64) {
            throw new IllegalArgumentException("signed identity too short");
        }
        try {
            byte[] payload = java.util.Arrays.copyOfRange(signed, 64, signed.length);
            IdPk idPk = IdPk.parseFrom(payload);
            if (idPk.getPk().size() != 32) {
                throw new IllegalArgumentException("peer public key must be 32 bytes");
            }
            return new PeerIdentity(idPk.getId(), idPk.getPk().toByteArray(),
                    idPk.getKxVersion(), idPk.getDtlsFingerprint());
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed identity blob", e);
        }
    }

    /** Compares the verified id against the id we asked for. */
    public boolean matches(String expectedId) {
        return expectedId == null || expectedId.equals(id);
    }

    @Override
    public String toString() {
        return "PeerIdentity{id=" + id + ", kx=" + kxVersion
                + ", pk=" + hex4(publicKey) + "…}";
    }

    private static String hex4(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(4, b.length); i++) {
            sb.append(String.format("%02x", b[i]));
        }
        return sb.toString();
    }
}
