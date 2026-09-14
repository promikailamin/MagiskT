//! SU policy database queries.
//!
//! Implements `MagiskD` methods for reading per-UID root policies
//! ([`get_root_settings`]), pruning stale entries ([`prune_su_access`]),
//! and checking whether a UID has been granted root ([`uid_granted_root`]).

use crate::daemon::{
    AID_APP_END, AID_APP_START, AID_ROOT, AID_SHELL, AID_USER_OFFSET, MagiskD, to_app_id,
    to_user_id,
};
use crate::db::DbArg::{Integer, Text};
use crate::db::{MultiuserMode, RootAccess, SqlTable, SqliteResult, SqliteReturn};
use crate::ffi::{DbValues, SuPolicy};
use base::{ResultExt, libc};

impl Default for SuPolicy {
    fn default() -> Self {
        SuPolicy::Query
    }
}

#[derive(Default)]
pub struct RootSettings {
    pub policy: SuPolicy,
    pub log: bool,
    pub notify: bool,
}

impl SqlTable for RootSettings {
    fn on_row(&mut self, columns: &[String], values: &DbValues) {
        for (i, column) in columns.iter().enumerate() {
            let val = values.get_int(i as i32);
            match column.as_str() {
                "policy" => self.policy.repr = val,
                "logging" => self.log = val != 0,
                "notification" => self.notify = val != 0,
                _ => {}
            }
        }
    }
}

struct UidList(Vec<(i32, bool, String)>);

impl SqlTable for UidList {
    fn on_row(&mut self, columns: &[String], values: &DbValues) {
        let mut uid = 0i32;
        let mut locked = false;
        let mut package_name = String::new();
        for (i, column) in columns.iter().enumerate() {
            match column.as_str() {
                "uid" => uid = values.get_int(i as i32),
                "locked" => locked = values.get_int(i as i32) != 0,
                "package_name" => {
                    let pkg = values.get_text(i as i32);
                    package_name.push_str(pkg);
                }
                _ => {}
            }
        }
        self.0.push((uid, locked, package_name));
    }
}

/// Look up the app ID of an installed package by stat-ing its data directory
/// across all users. Returns the app ID (not the full UID).
fn pkg_app_id(pkg: &str) -> Option<i32> {
    let users = std::fs::read_dir("/data/data").ok()?;
    for entry in users.flatten() {
        let path = entry.path().join(pkg);
        let path = path.to_str()?;
        let c_path = std::ffi::CString::new(path).ok()?;
        let mut st: libc::stat = unsafe { std::mem::zeroed() };
        if unsafe { libc::stat(c_path.as_ptr(), &mut st) } == 0 {
            return Some(to_app_id(st.st_uid as i32));
        }
    }
    None
}

impl MagiskD {
    pub fn get_root_settings(&self, uid: i32, settings: &mut RootSettings) -> SqliteResult<()> {
        self.db_exec_with_rows(
            "SELECT policy, logging, notification FROM policies \
             WHERE uid=? AND (until=0 OR until>strftime('%s', 'now'))",
            &[Integer(uid as i64)],
            settings,
        )
        .sql_result()?;

        // No policy for this UID: the app may have been reinstalled under a new UID
        // while a locked policy follows its package name. Remap and auto-grant.
        if settings.policy == SuPolicy::Query && self.remap_locked_policy(uid) {
            self.db_exec_with_rows(
                "SELECT policy, logging, notification FROM policies \
                 WHERE uid=? AND (until=0 OR until>strftime('%s', 'now'))",
                &[Integer(uid as i64)],
                settings,
            )
            .sql_result()?;
        }

        Ok(())
    }

    pub fn prune_su_access(&self) {
        let mut list = UidList(Vec::new());
        if self
            .db_exec_with_rows("SELECT uid, locked, package_name FROM policies", &[], &mut list)
            .sql_result()
            .log()
            .is_err()
        {
            return;
        }

        let app_list = self.get_app_no_list();
        let mut rm_uids = Vec::new();

        for (uid, locked, package_name) in list.0 {
            if locked {
                // Locked policies persist even if the app is uninstalled. If the package
                // was reinstalled and got a new UID, remap the policy to follow it.
                if !package_name.is_empty() {
                    if let Some(app_id) = pkg_app_id(&package_name) {
                        let new_uid = to_user_id(uid) * AID_USER_OFFSET + app_id;
                        if new_uid != uid {
                            // Remove any stale row at the target uid for the same package
                            // so the remap cannot hit a PRIMARY KEY conflict.
                            self.db_exec(
                                "DELETE FROM policies WHERE uid=? AND package_name=?",
                                &[Integer(new_uid as i64), Text(package_name.as_str())],
                            );
                            self.db_exec(
                                "UPDATE policies SET uid=? WHERE uid=? AND package_name=?",
                                &[
                                    Integer(new_uid as i64),
                                    Integer(uid as i64),
                                    Text(package_name.as_str()),
                                ],
                            );
                        }
                    }
                }
                continue;
            }
            let app_id = to_app_id(uid);
            if (AID_APP_START..=AID_APP_END).contains(&app_id) {
                let app_no = app_id - AID_APP_START;
                if !app_list.contains(app_no as usize) {
                    // The app_id is no longer installed
                    rm_uids.push(uid);
                }
            }
        }

        for uid in rm_uids {
            self.db_exec("DELETE FROM policies WHERE uid=?", &[Integer(uid as i64)]);
        }
    }

    /// On an SU request, if the requesting [uid] has no policy but the same
    /// package has a locked policy under an old UID (app reinstalled), remap it
    /// so the grant/lock follows the app. Returns whether a remap happened.
    fn remap_locked_policy(&self, uid: i32) -> bool {
        let target_app_id = to_app_id(uid);
        if !(AID_APP_START..=AID_APP_END).contains(&target_app_id) {
            return false;
        }

        let mut list = UidList(Vec::new());
        if self
            .db_exec_with_rows(
                "SELECT uid, locked, package_name FROM policies WHERE locked=1 AND package_name != ''",
                &[],
                &mut list,
            )
            .sql_result()
            .log()
            .is_err()
        {
            return false;
        }

        for (old_uid, _locked, package_name) in list.0 {
            if old_uid == uid {
                continue;
            }
            if let Some(app_id) = pkg_app_id(&package_name) {
                if app_id == target_app_id {
                    // Remove any stale row at the requesting uid, then move the lock
                    self.db_exec(
                        "DELETE FROM policies WHERE uid=? AND package_name=?",
                        &[Integer(uid as i64), Text(package_name.as_str())],
                    );
                    self.db_exec(
                        "UPDATE policies SET uid=? WHERE uid=?",
                        &[Integer(uid as i64), Integer(old_uid as i64)],
                    );
                    return true;
                }
            }
        }
        false
    }

    pub fn uid_granted_root(&self, mut uid: i32) -> bool {
        if uid == AID_ROOT {
            return true;
        }

        let cfg = match self.get_db_settings().log() {
            Ok(cfg) => cfg,
            Err(_) => return false,
        };

        // Check user root access settings
        match cfg.root_access {
            RootAccess::Disabled => return false,
            RootAccess::AppsOnly => {
                if uid == AID_SHELL {
                    return false;
                }
            }
            RootAccess::AdbOnly => {
                if uid != AID_SHELL {
                    return false;
                }
            }
            _ => {}
        }

        // Check multiuser settings
        match cfg.multiuser_mode {
            MultiuserMode::OwnerOnly => {
                if to_user_id(uid) != 0 {
                    return false;
                }
            }
            MultiuserMode::OwnerManaged => uid = to_app_id(uid),
            _ => {}
        }

        let mut granted = false;
        let mut output_fn =
            |_: &[String], values: &DbValues| granted = values.get_int(0) == SuPolicy::Allow.repr;
        self.db_exec_with_rows(
            "SELECT policy FROM policies WHERE uid=? AND (until=0 OR until>strftime('%s', 'now'))",
            &[Integer(uid as i64)],
            &mut output_fn,
        );

        granted
    }
}
