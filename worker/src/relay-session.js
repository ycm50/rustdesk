/**
 * Pairs one bridge socket with one viewer socket and forwards binary frames both ways.
 *
 * Nothing here understands the RustDesk protocol: the peer's own WSS transport already
 * frames the same bytes a raw TCP connection would carry.
 */
export class RelaySession {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.host = null;
    this.viewer = null;
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname !== "/connect") {
      return new Response("not found\n", { status: 404 });
    }

    const role = url.searchParams.get("role");
    if (role !== "bridge" && role !== "viewer") {
      return new Response("bad role\n", { status: 400 });
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    server.accept();

    if (role === "bridge") {
      this.replaceHost(server);
    } else {
      this.replaceViewer(server);
      // Wake the bridge: it holds one TCP connection to the peer, and the peer only speaks
      // first once per connection, so a viewer that arrives later would wait for a greeting
      // that was already consumed. A throw here would abort this fetch and take the viewer's
      // 101 with it, which is why the send is guarded.
      try {
        this.host?.send("viewer-up");
      } catch (err) {
        console.log("relay: viewer-up failed: " + (err && err.message ? err.message : err));
      }
    }

    server.addEventListener("message", (event) => {
      const peer = role === "bridge" ? this.viewer : this.host;
      if (!peer) return;
      try {
        peer.send(event.data);
      } catch (err) {
        // Swallowing this hid a broken direction for a whole debugging session: say it out loud.
        console.error(
          "relay: " + role + " -> peer send failed: " + (err && err.message ? err.message : err),
        );
      }
    });

    const drop = () => {
      if (role === "bridge") {
        console.log("relay: bridge socket closed");
        if (this.host === server) this.host = null;
      } else if (this.viewer === server) {
        console.log("relay: viewer socket closed");
        this.viewer = null;
        // The bridge still holds the peer connection it opened for that viewer, and the peer
        // greets only once per connection. Without this the next viewer would wait forever for
        // a greeting that was already spent.
        try {
          this.host?.send("viewer-down");
        } catch (err) {
          console.log("relay: viewer-down failed: " + (err && err.message ? err.message : err));
        }
      }
    };
    server.addEventListener("close", drop);
    server.addEventListener("error", drop);

    return new Response(null, { status: 101, webSocket: client });
  }

  /**
   * A reconnecting bridge (or a second viewer) replaces the stale socket instead of leaving
   * two half-pairs around.
   */
  replaceHost(socket) {
    const previous = this.host;
    this.host = socket;
    console.log("relay: bridge attached (replacing: " + Boolean(previous) + ")");
    if (previous) {
      try {
        previous.close(1000, "replaced");
      } catch {
        // already closed
      }
    }
  }

  replaceViewer(socket) {
    const previous = this.viewer;
    this.viewer = socket;
    console.log("relay: viewer attached (replacing: " + Boolean(previous) + ")");
    if (previous) {
      try {
        previous.close(1000, "replaced");
      } catch {
        // already closed
      }
    }
  }
}
