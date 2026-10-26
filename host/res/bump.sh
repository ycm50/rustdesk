#! /usr/bin/env bash
# Run from host/. The workflow and the other repository-level files are one level up.
sed -i "s/\b$1\b/$2/g" res/*spec res/PKGBUILD Cargo.toml ../.github/workflows/*yml flatpak/*json appimage/*yml libs/portable/Cargo.toml
cargo run # to bump version in cargo lock
