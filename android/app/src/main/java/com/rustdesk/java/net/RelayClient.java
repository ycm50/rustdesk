package com.rustdesk.java.net;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import hbb.Rendezvous.ConnType;
import hbb.Rendezvous.RequestRelay;
import hbb.Rendezvous.RendezvousMessage;

/**
 * Opens the relay leg of a session.
 *
 * <p>src/client.rs:create_relay connects to hbbr, sends one {@code RequestRelay} carrying the uuid
 * the rendezvous server handed out, and then treats the connection as the peer stream: the relay
 * bridges it to the other side, so the peer's {@code SignedId} arrives next.
 */
public final class RelayClient {

    /** hbbr listens one port above hbbs. */
    public static final int RELAY_PORT = 21117;

    private RelayClient() {}

    public static Transport open(String relayHost, String peerId, String uuid) throws IOException {
        return open(relayHost, RELAY_PORT, peerId, uuid);
    }

    public static Transport open(String relayHost, int port, String peerId, String uuid)
            throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(relayHost, port), 10_000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(0);

        Transport transport = new StreamTransport(socket);
        RequestRelay request = RequestRelay.newBuilder()
                .setId(peerId)
                .setUuid(uuid)
                .setLicenceKey("")
                .setConnType(ConnType.DEFAULT_CONN)
                .build();
        transport.send(RendezvousMessage.newBuilder().setRequestRelay(request).build()
                .toByteArray());
        return transport;
    }

    /** Splits "host:port" the way the rendezvous server reports relay servers. */
    public static String hostOf(String relayServer) {
        if (relayServer == null) return null;
        int colon = relayServer.lastIndexOf(':');
        if (colon > 0 && !relayServer.contains("]")) {
            return relayServer.substring(0, colon);
        }
        return relayServer;
    }

    public static int portOf(String relayServer, int fallback) {
        if (relayServer == null) return fallback;
        int colon = relayServer.lastIndexOf(':');
        if (colon > 0 && colon < relayServer.length() - 1) {
            try {
                return Integer.parseInt(relayServer.substring(colon + 1));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
