/**
 * RustDesk WSS relay.
 *
 * The desktop host runs `bridge/` next to it: the bridge listens on 127.0.0.1:21118 and
 * forwards every connection to this Worker over WSS. A viewer (the Java client) points its
 * ID field at `wss://<worker-host>/websocket`. The Worker never terminates the RustDesk
 * protocol: it only forwards binary frames, which is exactly what the peer's WSS transport
 * carries on a raw socket.
 *
 *   viewer  --WSS-->  /websocket           --\
 *                                            >  RelaySession (one per key)
 *   bridge  --WSS-->  /bridge?key=SECRET   --/
 *
 * The key identifies the host. Leaving it out of the viewer URL is optional: with a single
 * configured bridge the Worker resolves it from BRIDGE_KEY.
 */

export { RelaySession } from "./relay-session.js";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return new Response("ok\n", { headers: { "content-type": "text/plain" } });
    }

    if (url.pathname === "/bridge") {
      return relayUpgrade(request, env, url, "bridge");
    }

    if (url.pathname === "/websocket" || url.pathname === "/") {
      return relayUpgrade(request, env, url, "viewer");
    }

    return new Response("not found\n", { status: 404 });
  },
};

async function relayUpgrade(request, env, url, role) {
  if (request.headers.get("Upgrade") !== "websocket") {
    return new Response("expected websocket\n", { status: 426 });
  }

  const key = url.searchParams.get("key") || env.BRIDGE_KEY || "";
  if (!key) {
    return new Response("missing key\n", { status: 400 });
  }

  // Every key maps to exactly one Durable Object, so the two sides always land together.
  const id = env.RELAY.idFromName(key);
  const stub = env.RELAY.get(id);

  const target = new URL(request.url);
  target.pathname = "/connect";
  target.searchParams.set("role", role);
  return stub.fetch(new Request(target.toString(), request));
}
