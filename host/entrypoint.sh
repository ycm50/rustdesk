#!/bin/sh
# Start the headless RustDesk host built into this image.
#
# No vcpkg root, no Flutter assets, no sciter: the binary in target/release is the whole product.
# Arguments are passed straight through to it; with none, --headless (the CMD default) hosts this
# machine in the console.
set -e
cd "${RUSTDESK_HOST_DIR:-/src/host}"

if [ ! -x target/release/rustdesk ]; then
    echo "target/release/rustdesk is missing; build it with:" >&2
    echo "  cargo build --release --features system-libs" >&2
    exit 1
fi

exec target/release/rustdesk "$@"
