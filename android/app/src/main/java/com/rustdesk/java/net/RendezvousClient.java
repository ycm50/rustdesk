package com.rustdesk.java.net;

import com.google.protobuf.ByteString;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.UUID;

import hbb.Rendezvous.RendezvousMessage;
import hbb.Rendezvous.PunchHoleRequest;
import hbb.Rendezvous.PunchHoleResponse;
import hbb.Rendezvous.RegisterPeer;
import hbb.Rendezvous.RelayResponse;
import hbb.Rendezvous.RequestRelay;
import hbb.Rendezvous.RegisterPeerResponse;
import hbb.Rendezvous.RegisterPk;
import hbb.Rendezvous.RegisterPkResponse;

/**
 * Minimal rendezvous (hbbs) client: registers our device, then asks the server where a peer is.
 *
 * <p>Mirrors src/rendezvous_mediator.rs: register with our ed25519 public key, announce the id,
 * then send PunchHoleRequest for the peer we want and read the address back out of
 * PunchHoleResponse.
 */
public class RendezvousClient {

    public static final int DEFAULT_PORT = 21116;

    /** Result of a punch hole request. */
    public static final class PeerAddress {
        public final InetSocketAddress direct;
        public final String relayServer;
        public final byte[] peerPk;
        public final String failure;
        /** Relay pairing id, present when the server routed us through hbbr. */
        public final String uuid;

        PeerAddress(InetSocketAddress direct, String relayServer, byte[] peerPk, String failure,
                    String uuid) {
            this.direct = direct;
            this.relayServer = relayServer;
            this.peerPk = peerPk;
            this.failure = failure;
            this.uuid = uuid;
        }

        public boolean hasDirect() {
            return direct != null && direct.getPort() > 0;
        }

        public boolean hasRelay() {
            return uuid != null && !uuid.isEmpty()
                    && relayServer != null && !relayServer.isEmpty();
        }
    }

    private final String server;
    private final int port;
    private final String version;

    public RendezvousClient(String server, int port, String version) {
        this.server = server;
        this.port = port <= 0 ? DEFAULT_PORT : port;
        this.version = version;
    }

    /**
     * Registers {@code myId} (signing with {@code mySignPublicKey}) and resolves {@code peerId}.
     *
     * <p>The server opens with its own key exchange; until that is answered it treats everything
     * else as a protocol violation and closes, so it always runs first.
     */
    public PeerAddress resolve(String myId, byte[] mySignPublicKey, String peerId,
                               byte[] rendezvousPublicKey) throws IOException {
        return resolve(myId, mySignPublicKey, peerId, rendezvousPublicKey, null);
    }

    /**
     * @param registerUuid the 16 byte device uuid the server has on file for {@code myId}. The
     *     server pins it on first contact and rejects later registrations that present a different
     *     one with {@code UUID_MISMATCH}, so callers must persist it. A null value keeps the old
     *     behaviour of using a random uuid, which only works for ids that were never registered.
     */
    public PeerAddress resolve(String myId, byte[] mySignPublicKey, String peerId,
                               byte[] rendezvousPublicKey, byte[] registerUuid)
            throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(server, port), 10_000);
        socket.setSoTimeout(15_000);
        Transport raw = new StreamTransport(socket);
        Transport transport = raw;
        try {
            if (rendezvousPublicKey != null) {
                transport = RendezvousKeyExchange.secure(raw, rendezvousPublicKey);
            }
            // Announcing the key is enough for a viewer: the server answers PunchHoleRequest on
            // the same connection, and RegisterPeer is only used by hosts publishing themselves.
            registerPk(transport, myId, mySignPublicKey, registerUuid);
            return punchHole(transport, peerId);
        } finally {
            transport.close();
        }
    }

    /** Backwards compatible entry point that skips the server key exchange. */
    public PeerAddress resolve(String myId, byte[] mySignPublicKey, String peerId)
            throws IOException {
        return resolve(myId, mySignPublicKey, peerId, null, null);
    }

    private void registerPk(Transport transport, String myId, byte[] signPk, byte[] registerUuid)
            throws IOException {
        byte[] uuid = registerUuid != null && registerUuid.length == 16
                ? registerUuid
                : randomUuidBytes();
        RendezvousMessage request = RendezvousMessage.newBuilder()
                .setRegisterPk(RegisterPk.newBuilder()
                        .setId(myId)
                        .setUuid(ByteString.copyFrom(uuid))
                        .setPk(ByteString.copyFrom(signPk))
                        .setNoRegisterDevice(true)
                        .build())
                .build();
        transport.send(request.toByteArray());

        while (true) {
            byte[] frame = transport.receive();
            if (frame == null) {
                throw new IOException("rendezvous closed during RegisterPk");
            }
            RendezvousMessage message = RendezvousMessage.parseFrom(frame);
            if (message.hasRegisterPkResponse()) {
                RegisterPkResponse response = message.getRegisterPkResponse();
                int result = response.getResultValue();
                if (result == RegisterPkResponse.Result.OK_VALUE
                        || result == RegisterPkResponse.Result.NOT_SUPPORT_VALUE
                        || result == RegisterPkResponse.Result.NOT_DEPLOYED_VALUE) {
                    return;
                }
                throw new IOException("RegisterPk rejected: " + response.getResult());
            }
            // Other server chatter (ConfigUpdate, TestDelay, …) is ignored.
        }
    }

    /** @return true when the server asked us to (re)announce our public key */
    private boolean registerPeer(Transport transport, String myId) throws IOException {
        RendezvousMessage request = RendezvousMessage.newBuilder()
                .setRegisterPeer(RegisterPeer.newBuilder().setId(myId).setSerial(0).build())
                .build();
        transport.send(request.toByteArray());

        while (true) {
            byte[] frame = transport.receive();
            if (frame == null) {
                throw new IOException("rendezvous closed during RegisterPeer");
            }
            RendezvousMessage message = RendezvousMessage.parseFrom(frame);
            if (message.hasRegisterPeerResponse()) {
                return message.getRegisterPeerResponse().getRequestPk();
            }
        }
    }

    private PeerAddress punchHole(Transport transport, String peerId) throws IOException {
        PunchHoleRequest request = PunchHoleRequest.newBuilder()
                .setId(peerId)
                .setVersion(version)
                .setConnType(hbb.Rendezvous.ConnType.DEFAULT_CONN)
                .build();
        transport.send(RendezvousMessage.newBuilder().setPunchHoleRequest(request).build().toByteArray());

        while (true) {
            byte[] frame = transport.receive();
            if (frame == null) {
                throw new IOException("rendezvous closed during PunchHoleRequest");
            }
            RendezvousMessage message = RendezvousMessage.parseFrom(frame);
            if (message.hasPunchHoleResponse()) {
                PunchHoleResponse response = message.getPunchHoleResponse();
                InetSocketAddress direct = AddrMangle.decode(response.getSocketAddr().toByteArray());
                String failure = response.getSocketAddr().isEmpty()
                        ? response.getFailure().name()
                        : null;
                if (failure == null && response.getOtherFailure() != null
                        && !response.getOtherFailure().isEmpty()) {
                    failure = response.getOtherFailure();
                }
                String relayServer = response.getRelayServer();
                String uuid = null;
                if (direct == null && relayServer != null && !relayServer.isEmpty()) {
                    // No direct route: ask the server to pair us through the relay. The uuid it
                    // hands back identifies this pairing on the hbbr connection.
                    uuid = requestRelay(transport, peerId, relayServer);
                }
                return new PeerAddress(direct, relayServer,
                        response.getPk().toByteArray(), failure, uuid);
            }
        }
    }

    /** Sends RequestRelay on the rendezvous connection and reads the pairing uuid back. */
    private String requestRelay(Transport transport, String peerId, String relayServer)
            throws IOException {
        String uuid = UUID.randomUUID().toString();
        RequestRelay request = RequestRelay.newBuilder()
                .setId(peerId)
                .setUuid(uuid)
                .setRelayServer(relayServer)
                .setSecure(true)
                .build();
        transport.send(RendezvousMessage.newBuilder().setRequestRelay(request).build().toByteArray());

        while (true) {
            byte[] frame = transport.receive();
            if (frame == null) {
                return null;
            }
            RendezvousMessage message = RendezvousMessage.parseFrom(frame);
            if (message.hasRelayResponse()) {
                RelayResponse response = message.getRelayResponse();
                if (!response.getRefuseReason().isEmpty()) {
                    throw new IOException("relay refused: " + response.getRefuseReason());
                }
                String returned = response.getUuid();
                return returned == null || returned.isEmpty() ? uuid : returned;
            }
        }
    }

    private static byte[] randomUuidBytes() {
        UUID uuid = UUID.randomUUID();
        long msb = uuid.getMostSignificantBits();
        long lsb = uuid.getLeastSignificantBits();
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (msb >>> (56 - 8 * i));
            out[8 + i] = (byte) (lsb >>> (56 - 8 * i));
        }
        return out;
    }
}



