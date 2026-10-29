# Headless RustDesk host, built against the distribution's libraries.
#
#   docker build -t rustdesk-host -f host/Dockerfile host
#   docker run --rm -it --name rustdesk-host rustdesk-host
#
# Context is host/. There is no vcpkg, no Flutter SDK and no GUI toolchain in this image: the
# codecs come from libvpx-dev/libaom-dev/libyuv-dev/libopus-dev through pkg-config, which is what
# `--features system-libs` selects (Cargo.toml, libs/scrap/build.rs, libs/magnum-opus/build.rs).
FROM debian:trixie-slim

ARG DEBIAN_FRONTEND=noninteractive

ENV CARGO_HOME=/usr/local/cargo \
    RUSTUP_HOME=/usr/local/rustup \
    PATH=/usr/local/cargo/bin:$PATH \
    RUSTDESK_HOST_DIR=/src/host

WORKDIR /src

# Toolchain: cc/cxx for the -sys crates that compile C (kcp-sys, webm-sys, magnum-opus,
# libsodium-sys), pkg-config for --features system-libs, libclang for bindgen (scrap, v4l2-sys-mit),
# nasm/yasm for mozjpeg-sys, cmake for the -sys crates that drive it, perl+make for openssl-src.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        clang \
        cmake \
        curl \
        git \
        libclang-dev \
        libssl-dev \
        llvm \
        nasm \
        ninja-build \
        perl \
        pkg-config \
        yasm \
        zlib1g-dev \
    && rm -rf /var/lib/apt/lists/*

# Codecs + capture/audio/system libraries. The four codec -dev packages are the whole point of
# system-libs; the rest is what the default feature set resolves through pkg-config and links
# against (gstreamer, dbus, wayland, drm/gbm/va, x11/xcb, pulse/alsa, gtk, udev/systemd).
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        libvpx-dev \
        libaom-dev \
        libyuv-dev \
        libopus-dev \
        libx264-dev \
        libx265-dev \
        libgstreamer1.0-dev \
        libgstreamer-plugins-base1.0-dev \
        libgstreamer-plugins-bad1.0-dev \
        libglib2.0-dev \
        libgtk-3-dev \
        libasound2-dev \
        libpipewire-0.3-dev \
        libpulse-dev \
        libdbus-1-dev \
        libwayland-dev \
        libdrm-dev \
        libgbm-dev \
        libva-dev \
        libx11-dev \
        libxext-dev \
        libxfixes-dev \
        libxdamage-dev \
        libxtst-dev \
        libxi-dev \
        libxrandr-dev \
        libxcursor-dev \
        libxft-dev \
        libxinerama-dev \
        libxmu-dev \
        libxrender-dev \
        libxss-dev \
        libxxf86vm-dev \
        libxkbcommon-dev \
        libxkbcommon-x11-dev \
        libxcb1-dev \
        libxcb-randr0-dev \
        libxcb-shape0-dev \
        libxcb-xfixes0-dev \
        libxcb-render0-dev \
        libxcb-shm0-dev \
        libevdev-dev \
        libxdo-dev \
        libudev-dev \
        libsystemd-dev \
        libpam0g-dev \
    && rm -rf /var/lib/apt/lists/*

# A missing .pc file is a "failed to run custom build command" 30 minutes into the build, so fail
# here and name the package instead.
RUN set -e; for pc in vpx aom opus gstreamer-1.0 dbus-1 wayland-client libdrm gtk+-3.0; do \
        printf '%-22s %s\n' "$pc" "$(pkg-config --modversion "$pc")"; \
        pkg-config --exists "$pc"; \
    done

# Debian's libyuv-dev may ship libyuv.h plus the library and no .pc file (Ubuntu's does not, which is
# why CI synthesizes one), and scrap/build.rs asks for libyuv through pkg-config. Only write the
# fallback when pkg-config cannot find it, so a distro-provided file is never shadowed.
RUN if ! pkg-config --exists libyuv; then \
        test -f /usr/include/libyuv.h && \
        mkdir -p /usr/local/lib/pkgconfig && \
        { \
            echo 'Name: libyuv'; \
            echo 'Description: YUV conversion/scaling library (distro package ships no .pc file)'; \
            echo 'Version: 0.0'; \
            echo 'Libs: -lyuv'; \
            echo 'Cflags: -I/usr/include'; \
        } > /usr/local/lib/pkgconfig/libyuv.pc; \
        pkg-config --cflags --libs libyuv; \
    fi

RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs \
    | sh -s -- -y --profile minimal --default-toolchain stable

COPY . /src/host
WORKDIR /src/host
RUN cargo build --release --features system-libs

RUN chmod +x /src/host/entrypoint.sh

# The image holds one thing: the headless host, and --headless is the default. The extra client
# subcommands (--connect/--terminal/--file-transfer/--port-forward) are Windows-only in this tree,
# so on Linux argv is limited to what host/src/terminal/mod.rs accepts there (see TERMINAL.md).
ENTRYPOINT ["/src/host/entrypoint.sh"]
CMD ["--headless"]
