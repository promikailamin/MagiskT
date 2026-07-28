//! Build script for `init-rs`.
//!
//! Generates CXX bindings for the init crate via [`gen_cxx_binding`].

use crate::codegen::gen_cxx_binding;

#[path = "../include/codegen.rs"]
mod codegen;

fn main() {
    gen_cxx_binding("init-rs");
}
