//! Shared build script logic — compiles protobuf definitions.
//!
//! Used by the build scripts of the crates that depend on `pb-rs`, via
//! `#[path = "../include/protocodegen.rs"] mod protocodegen;`. Kept apart
//! from `codegen.rs` because the crates without protobuf definitions must
//! not gain a `pb-rs` build dependency.

use std::fs;
use std::path::{Path, PathBuf};
use std::process;

use pb_rs::ConfigBuilder;
use pb_rs::types::FileDescriptor;

/// Files pb-rs emits for a single protobuf definition compiled with
/// `single_module`.
fn generated_files(proto_file: &str) -> Vec<String> {
    let Some(stem) = proto_file.strip_suffix(".proto") else {
        eprintln!("error occurred: protobuf definition must end with .proto: {proto_file}");
        process::exit(1);
    };
    vec![format!("{stem}.rs"), "mod.rs".to_owned()]
}

/// Compile a protobuf definition and publish the generated sources.
///
/// pb-rs writes its output non-atomically: a `File::create` for the module
/// body, then a read-modify-write append for the `mod.rs` index. A build
/// script runs once per target triple and cargo is free to run those
/// invocations concurrently, so generating straight into the source tree
/// lets one invocation observe a half-written file produced by another.
/// That is a build failure with a nonsensical syntax error pointing at
/// generated code.
///
/// The sources are therefore generated into the per-target `OUT_DIR`, which
/// nothing else shares, and then published into `proto_dir` with a rename so
/// that readers only ever see a complete file.
#[allow(clippy::unwrap_used)]
pub fn gen_proto_sources(proto_dir: &str, proto_file: &str) {
    let gen_dir = PathBuf::from(std::env::var("OUT_DIR").unwrap()).join("proto");
    fs::create_dir_all(&gen_dir).unwrap();

    let cb = ConfigBuilder::new(
        &[PathBuf::from(proto_dir).join(proto_file)],
        None,
        Some(&gen_dir),
        &[PathBuf::from(".")],
    )
    .unwrap();
    FileDescriptor::run(
        &cb.single_module(true)
            .dont_use_cow(true)
            .generate_getters(true)
            .build(),
    )
    .unwrap();

    for file in generated_files(proto_file) {
        let src = gen_dir.join(file);
        let dst = Path::new(proto_dir).join(file);
        let bytes = fs::read(&src).unwrap();
        // Only touch the source tree when the contents actually changed, so
        // cargo does not see a needless mtime bump and rebuild the crate.
        if fs::read(&dst).ok().as_deref() == Some(bytes.as_slice()) {
            continue;
        }
        // The pid keeps concurrent build script invocations from fighting
        // over the same scratch file.
        let tmp = PathBuf::from(format!("{}.{}.tmp", dst.display(), process::id()));
        fs::write(&tmp, bytes).unwrap();
        fs::rename(&tmp, &dst).unwrap();
    }
}
