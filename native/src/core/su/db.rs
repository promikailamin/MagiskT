//! SU root-grant checks.
//!
//! The policy database is gone; the global `superuser` setting and the
//! denylist decide who is entitled to root. When `superuser` is disabled
//! (the default), only the Magisk manager app is granted root. These checks
//! are consumed by Zygisk to decide whether root needs to be hidden from a
//! process.

use crate::daemon::{AID_ROOT, MagiskD, to_user_id};
use crate::ffi::{DbEntryKey, is_deny_target_uid};

impl MagiskD {
    /// Whether `uid` is entitled to root. Always `true` for root itself,
    /// `false` for denylisted UIDs, and otherwise gated by the global
    /// `superuser` setting (the manager app is always entitled to root).
    pub fn uid_granted_root(&self, uid: i32) -> bool {
        if uid == AID_ROOT {
            return true;
        }
        if is_deny_target_uid(uid) {
            return false;
        }
        self.get_db_setting(DbEntryKey::Superuser) != 0
            || self.get_manager_uid(to_user_id(uid)) == uid
    }
}
