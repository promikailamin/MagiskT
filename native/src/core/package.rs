//! Magisk Manager detection.
//!
//! Locates the Magisk Manager package on disk and resolves the app ID of
//! its data directory, which is used to identify the manager process.

use crate::consts::APP_PACKAGE_NAME;
use crate::daemon::MagiskD;
use base::{FsPathBuilder, cstr};

impl MagiskD {
    fn get_package_uid(&self, user: i32, pkg: &str) -> i32 {
        let path = cstr::buf::default()
            .join_path(self.app_data_dir())
            .join_path_fmt(user)
            .join_path(pkg);
        path.get_attr()
            .map(|attr| attr.st.st_uid as i32)
            .unwrap_or(-1)
    }

    pub fn get_manager_uid(&self, user: i32) -> i32 {
        self.get_package_uid(user, APP_PACKAGE_NAME)
    }
}
