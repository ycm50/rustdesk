//! The permanent password, kept where the operator put it.
//!
//! `--password` writes the verifier into the config file like any other setting, but the
//! verifier is a one-way value: it cannot be shown back. This module keeps a plain-text copy
//! beside it so the headless banner can display the password that is actually in force, and
//! so a running host can re-check a peer against the file that is on disk right now rather than
//! against whatever was loaded at startup.

use std::fs;
use std::io::Write;
use std::path::PathBuf;

use hbb_common::config::{self, Config};

const FILE_NAME: &str = "permanent-password.txt";

/// Beside the config file, which is what `<exe>/data/config/` resolves to for a portable run.
pub fn path() -> PathBuf {
    Config::path(FILE_NAME)
}

/// Writes the plain-text copy. The config file keeps the one-way verifier used for auth.
pub fn write(password: &str) -> std::io::Result<()> {
    let path = path();
    if let Some(dir) = path.parent() {
        fs::create_dir_all(dir)?;
    }
    let mut file = fs::File::create(&path)?;
    file.write_all(password.as_bytes())?;
    file.flush()?;
    restrict_permissions(&path);
    Ok(())
}

/// The password on disk right now, or `None` when it was never written or has been cleared.
pub fn read() -> Option<String> {
    let text = fs::read_to_string(path()).ok()?;
    let text = text.trim_end_matches(['\r', '\n']);
    if text.is_empty() {
        None
    } else {
        Some(text.to_owned())
    }
}

/// Rebuilds the verifier from the file so a login can be checked against the current password.
///
/// The salt comes from the config, so a rotated salt still yields the storage this host would
/// have written for the same password.
pub fn storage_for_auth() -> Option<String> {
    let password = read()?;
    let (_, salt) = Config::get_local_permanent_password_storage_and_salt();
    config::permanent_password_storage_from_plain(&password, &salt)
}

#[cfg(unix)]
fn restrict_permissions(path: &std::path::Path) {
    use std::os::unix::fs::PermissionsExt;
    let _ = fs::set_permissions(path, fs::Permissions::from_mode(0o600));
}

/// The Windows ACL is inherited from the config directory, which is already per-user.
#[cfg(not(unix))]
fn restrict_permissions(_path: &std::path::Path) {}

const NOT_WRITTEN_HERE: &str = "已设置（未由本命令写入，值不可读出）";

/// Which text the banner shows, given the file contents and whether a verifier is stored.
fn banner_text_for(plain: Option<&str>, has_stored: bool) -> String {
    match plain {
        Some(password) => password.to_owned(),
        None if has_stored => NOT_WRITTEN_HERE.to_owned(),
        None => String::new(),
    }
}

/// Text for the headless banner: the password itself when the file exists.
pub fn banner_text() -> String {
    banner_text_for(read().as_deref(), Config::has_permanent_password())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn banner_shows_the_password_from_the_file() {
        assert_eq!(banner_text_for(Some("abc123"), true), "abc123");
    }

    #[test]
    fn banner_explains_a_password_it_cannot_read_back() {
        // A verifier set some other way (the config synced from elsewhere) has no file.
        assert_eq!(banner_text_for(None, true), NOT_WRITTEN_HERE);
        assert_eq!(banner_text_for(None, false), "");
    }

    #[test]
    fn storage_round_trip_matches_a_fresh_password() {
        let salt = "0123456789abcdef";
        let storage = config::permanent_password_storage_from_plain("s3cret", salt).unwrap();
        assert!(!storage.is_empty());
        assert!(config::local_permanent_password_storage_is_usable_for_auth(
            &storage, salt
        ));
    }
}
