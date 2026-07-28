//! ELF cleaner utility for Magisk built binaries.
//!
//! Removes unsupported dynamic section entries (DT_RPATH, DT_RUNPATH,
//! AArch64 PAC/BTI/VARIANT_PCS) and filters DT_FLAGS_1 to only keep
//! DF_1_NOW and DF_1_GLOBAL. Adapted from termux-elf-cleaner.

use object::build::elf::{Builder, Dynamic, SectionData};
use object::elf;
use std::{env, fs};

// Missing ELF constants not defined in the object crate
const DT_AARCH64_BTI_PLT: u32 = elf::DT_LOPROC + 1;
const DT_AARCH64_PAC_PLT: u32 = elf::DT_LOPROC + 3;
const DT_AARCH64_VARIANT_PCS: u32 = elf::DT_LOPROC + 5;

// Only these DT_FLAGS_1 values are kept
const SUPPORTED_DT_FLAGS: u32 = elf::DF_1_NOW | elf::DF_1_GLOBAL;

/// Log removal of a dynamic section entry
fn print_remove_dynamic(name: &str, path: &str) {
    println!("Removing dynamic section entry {} in '{}'", name, path);
}

/// Strip unsupported ELF dynamic entries from a single binary
fn process_elf(path: &str) -> anyhow::Result<()> {
    let bytes = fs::read(path)?;
    let mut elf = Builder::read(bytes.as_slice())?;
    let is_aarch64 = elf.header.e_machine == elf::EM_AARCH64;

    elf.sections.iter_mut().for_each(|section| {
        if let SectionData::Dynamic(entries) = &mut section.data {
            // Remove unsupported entries
            entries.retain(|e| {
                let tag = e.tag();
                match tag {
                    elf::DT_RPATH => {
                        print_remove_dynamic("DT_RPATH", path);
                        return false;
                    }
                    elf::DT_RUNPATH => {
                        print_remove_dynamic("DT_RUNPATH", path);
                        return false;
                    }
                    _ => {}
                }
                if is_aarch64 {
                    match tag {
                        DT_AARCH64_BTI_PLT => {
                            print_remove_dynamic("DT_AARCH64_BTI_PLT", path);
                            return false;
                        }
                        DT_AARCH64_PAC_PLT => {
                            print_remove_dynamic("DT_AARCH64_PAC_PLT", path);
                            return false;
                        }
                        DT_AARCH64_VARIANT_PCS => {
                            print_remove_dynamic("DT_AARCH64_VARIANT_PCS", path);
                            return false;
                        }
                        _ => {}
                    }
                }
                true
            });
            // Remove unsupported flags
            for entry in entries.iter_mut() {
                if let Dynamic::Integer { tag, val } = entry {
                    if *tag == elf::DT_FLAGS_1 {
                        let new_flags = *val & SUPPORTED_DT_FLAGS as u64;
                        if new_flags != *val {
                            println!(
                                "Replacing unsupported DT_FLAGS_1 {:#x} with {:#x} in '{}'",
                                *val, new_flags, path
                            );
                            *val = new_flags;
                        }
                        break;
                    }
                }
            }
        }
    });

    let mut out_bytes = Vec::new();
    elf.write(&mut out_bytes)?;
    fs::write(path, &out_bytes)?;
    Ok(())
}

/// Entry point: process each file path given on the command line
fn main() -> anyhow::Result<()> {
    env::args().skip(1).try_for_each(|s| process_elf(&s))
}
