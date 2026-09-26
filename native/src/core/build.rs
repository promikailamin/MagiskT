//! Build script for `core-rs`.
//!
//! Generates CXX bindings via [`gen_cxx_binding`] and compiles the
//! protobuf definition for persistent properties.

use crate::codegen::gen_cxx_binding;
use crate::protocodegen::gen_proto_sources;

#[path = "../include/codegen.rs"]
mod codegen;

#[path = "../include/protocodegen.rs"]
mod protocodegen;

/// Directory the generated protobuf sources are published to.
const PROTO_DIR: &str = "resetprop/proto";
/// The protobuf definition to compile.
const PROTO_FILE: &str = "persistent_properties.proto";

fn main() {
    println!("cargo:rerun-if-changed={PROTO_DIR}/{PROTO_FILE}");

    gen_cxx_binding("core-rs");
    gen_proto_sources(PROTO_DIR, PROTO_FILE);
}
