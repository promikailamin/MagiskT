//! SU root-grant checks.
//!
//! The policy database is gone: root is granted to every caller by default.
//! The only gate is the denylist, so [`MagiskD::uid_granted_root`] simply
//! reports whether a UID is free of denylist entries. It is consumed by
//! Zygisk to decide whether root needs to be hidden from a process.

use crate::daemon::{AID_ROOT, MagiskD};
use crate::ffi::is_deny_target_uid;

impl MagiskD {
    /// Whether `uid` is entitled to root, i.e. it is not covered by the
    /// denylist. Always `true` for root itself.
    pub fn uid_granted_root(&self, uid: i32) -> bool {
        uid == AID_ROOT || !is_deny_target_uid(uid)
    }
}
