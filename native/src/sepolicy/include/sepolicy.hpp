/**
 * SELinux policy header defining standard Android sepolicy directory paths
 * and including the auto-generated CXX bridge header (policy-rs.hpp).
 *
 * Path constants reference the standard AOSP partition layout for SELinux
 * policy files under /system, /vendor, /product, /odm, and /system_ext.
 * selinuxfs constants point to the kernel interface at /sys/fs/selinux.
 */
#pragma once

#include <cstdlib>
#include <string>

#include <base.hpp>

#include "../policy-rs.hpp"

// Standard AOSP sepolicy directory locations (partition-specific policy files)
#define PLAT_POLICY_DIR     "/system/etc/selinux/"
#define VEND_POLICY_DIR     "/vendor/etc/selinux/"
#define PROD_POLICY_DIR     "/product/etc/selinux/"
#define ODM_POLICY_DIR      "/odm/etc/selinux/"
#define SYSEXT_POLICY_DIR   "/system_ext/etc/selinux/"

/** Path to the platform CIL source policy (the main system policy) */
#define SPLIT_PLAT_CIL      PLAT_POLICY_DIR "plat_sepolicy.cil"

// selinuxfs (kernel-based SELinux filesystem interface) paths
#define SELINUX_MNT         "/sys/fs/selinux"

/** File containing the current kernel's expected policy version number (integer) */
#define SELINUX_VERSION     SELINUX_MNT "/policyvers"
