//! SU request handling daemon side.
//!
//! [`MagiskD::su_daemon_handler`] receives an SU request from a client,
//! grants it unconditionally unless the requester is covered by the
//! denylist, forks a child, and hands control to the C++
//! `exec_root_shell`.
//!
//! There is no policy database, no user prompt, and no dependency on the
//! Magisk app: the daemon is the sole authority on who may gain root.

use crate::daemon::{AID_ROOT, MagiskD};
use crate::ffi::{DbEntryKey, MntNsMode, SuPolicy, SuRequest, exec_root_shell, is_deny_target_uid};
use crate::socket::IpcRead;
use base::{ResultExt, WriteExt, debug, error, exit_on_error, libc, warn};
use std::os::fd::{AsRawFd, IntoRawFd};
use std::os::unix::net::{UCred, UnixStream};

const DEFAULT_SHELL: &str = "/system/bin/sh";

impl Default for SuRequest {
    fn default() -> Self {
        SuRequest {
            target_uid: AID_ROOT,
            target_pid: -1,
            login: false,
            keep_env: false,
            drop_cap: false,
            shell: DEFAULT_SHELL.to_string(),
            command: "".to_string(),
            context: "".to_string(),
            gids: vec![],
        }
    }
}

/// Decide whether `uid` may gain root.
///
/// Root is granted to every caller. The single exception is the denylist:
/// an app covered by a denylist entry is never given root.
fn root_allowed(uid: i32) -> bool {
    if uid == AID_ROOT {
        return true;
    }
    if is_deny_target_uid(uid) {
        warn!("su: uid=[{uid}] is on the denylist, request rejected");
        return false;
    }
    debug!("su: uid=[{uid}] auto-granted root");
    true
}

impl MagiskD {
    pub fn su_daemon_handler(&self, mut client: UnixStream, cred: UCred) {
        debug!(
            "su: request from uid=[{}], pid=[{}], client=[{}]",
            cred.uid,
            cred.pid.unwrap_or(-1),
            client.as_raw_fd()
        );

        let mut req = match client.read_decodable::<SuRequest>().log() {
            Ok(req) => req,
            Err(_) => {
                warn!("su: remote process probably died, abort");
                client.write_pod(&SuPolicy::Deny.repr).ok();
                return;
            }
        };

        if !root_allowed(cred.uid as i32) {
            client.write_pod(&SuPolicy::Deny.repr).ok();
            return;
        }

        let mnt_ns = MntNsMode {
            repr: self.get_db_setting(DbEntryKey::SuMntNs),
        };

        // Root access is granted. Fork a child root process and monitor its exit value.
        let child = unsafe { libc::fork() };
        if child == 0 {
            debug!("su: fork handler");

            // Abort upon any error occurred
            exit_on_error(true);

            // ack
            client.write_pod(&0).ok();

            exec_root_shell(client.into_raw_fd(), cred.pid.unwrap_or(-1), &mut req, mnt_ns);
            return;
        }
        if child < 0 {
            error!("su: fork failed, abort");
            return;
        }

        // Wait result
        debug!("su: waiting child pid=[{}]", child);
        let mut status = 0;
        let code = unsafe {
            if libc::waitpid(child, &mut status, 0) > 0 {
                libc::WEXITSTATUS(status)
            } else {
                -1
            }
        };
        debug!("su: return code=[{}]", code);
        client.write_pod(&code).ok();
    }
}
