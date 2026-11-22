#![forbid(unsafe_code)]

#[path = "../src/imports.rs"]
mod imports;

use imports::{Import, LookupError, resolve_imports};

fn put16(bytes: &mut [u8], at: usize, value: u16) {
    bytes[at..at + 2].copy_from_slice(&value.to_le_bytes());
}
fn put32(bytes: &mut [u8], at: usize, value: u32) {
    bytes[at..at + 4].copy_from_slice(&value.to_le_bytes());
}
fn put64(bytes: &mut [u8], at: usize, value: u64) {
    bytes[at..at + 8].copy_from_slice(&value.to_le_bytes());
}

struct Fixture {
    bytes: Vec<u8>,
    dynamic: usize,
    symbols: usize,
    relocations: usize,
    slots: usize,
}
impl Fixture {
    fn new(shift: usize, original: u64) -> Self {
        let mut f = Self {
            bytes: vec![0; 0x1000 + shift],
            dynamic: 0x100 + shift,
            symbols: 0x300 + shift,
            relocations: 0x500 + shift,
            slots: 0x800 + shift,
        };
        let strings = 0x400 + shift;
        f.bytes[..7].copy_from_slice(b"\x7fELF\x02\x01\x01");
        put16(&mut f.bytes, 16, 3);
        put16(&mut f.bytes, 18, 183);
        put32(&mut f.bytes, 20, 1);
        put16(&mut f.bytes, 52, 64);
        let names = b"\0target\0other\0target_extra\0";
        f.bytes[strings..strings + names.len()].copy_from_slice(names);
        for (index, (tag, value)) in [
            (5, strings),
            (10, names.len()),
            (6, f.symbols),
            (11, 24),
            (23, f.relocations),
            (2, 48),
            (20, 7),
            (30, 8),
            (0, 0),
        ]
        .into_iter()
        .enumerate()
        {
            put64(&mut f.bytes, f.dynamic + index * 16, tag);
            put64(&mut f.bytes, f.dynamic + index * 16 + 8, value as u64);
        }
        for (index, name) in [1, 8].into_iter().enumerate() {
            let symbol = f.symbols + (index + 1) * 24;
            put32(&mut f.bytes, symbol, name);
            f.bytes[symbol + 4] = 0x12;
            let relocation = f.relocations + index * 24;
            put64(&mut f.bytes, relocation, (f.slots + index * 8) as u64);
            put64(
                &mut f.bytes,
                relocation + 8,
                (((index + 1) as u64) << 32) | 1026,
            );
            put64(
                &mut f.bytes,
                f.slots + index * 8,
                original + index as u64 * 32,
            );
        }
        f
    }
    fn resolve<const N: usize>(&self, names: [&[u8]; N]) -> Result<[Import; N], LookupError> {
        resolve_imports(
            |offset, out| {
                let end = offset
                    .checked_add(out.len())
                    .ok_or(LookupError::OutOfBounds)?;
                out.copy_from_slice(
                    self.bytes
                        .get(offset..end)
                        .ok_or(LookupError::OutOfBounds)?,
                );
                Ok(())
            },
            (self.dynamic, 9 * 16),
            names,
        )
    }
}

#[test]
fn imports_follow_moved_tables_and_slots() {
    for (shift, original) in [(0, 0x70001000), (0x1000, 0x90007000)] {
        let f = Fixture::new(shift, original);
        assert_eq!(
            f.resolve([b"target".as_slice(), b"other"]).unwrap(),
            [Import { slot: f.slots }, Import { slot: f.slots + 8 },]
        );
    }
}

#[test]
fn names_are_exact_and_missing_or_duplicate_queries_fail() {
    let f = Fixture::new(0, 0x10000);
    for name in [b"targ".as_slice(), b"target_extra", b"TARGET"] {
        assert_eq!(f.resolve([name]), Err(LookupError::MissingSymbol));
    }
    for name in [b"".as_slice(), b"target\0"] {
        assert_eq!(f.resolve([name]), Err(LookupError::InvalidTable));
    }
    assert_eq!(
        f.resolve([b"target".as_slice(), b"target"]),
        Err(LookupError::AmbiguousSymbol)
    );
}

#[test]
fn duplicate_imports_and_aliased_slots_fail() {
    let mut f = Fixture::new(0, 0x10000);
    put32(&mut f.bytes, f.symbols + 48, 1);
    assert_eq!(
        f.resolve([b"target".as_slice()]),
        Err(LookupError::AmbiguousSymbol)
    );
    put32(&mut f.bytes, f.symbols + 48, 8);
    put64(&mut f.bytes, f.relocations + 24, f.slots as u64);
    assert_eq!(
        f.resolve([b"target".as_slice(), b"other"]),
        Err(LookupError::AmbiguousSymbol)
    );
}

#[test]
fn malformed_dynamic_tables_fail() {
    let mut f = Fixture::new(0, 0x10000);
    put64(&mut f.bytes, f.dynamic + 8 * 16, 1);
    assert_eq!(
        f.resolve([b"target".as_slice()]),
        Err(LookupError::InvalidTable)
    );
    let mut f = Fixture::new(0, 0x10000);
    put64(&mut f.bytes, f.dynamic + 7 * 16, 5);
    assert_eq!(
        f.resolve([b"target".as_slice()]),
        Err(LookupError::InvalidTable)
    );
    let mut f = Fixture::new(0, 0x10000);
    put64(&mut f.bytes, f.dynamic + 5 * 16 + 8, 47);
    assert_eq!(
        f.resolve([b"target".as_slice()]),
        Err(LookupError::InvalidTable)
    );
}

#[test]
fn unsupported_architecture_lazy_binding_and_abi_fail() {
    for field in [18, 7 * 16 + 0x100 + 8, 6 * 16 + 0x100 + 8, 0x300 + 24 + 6] {
        let mut f = Fixture::new(0, 0x10000);
        f.bytes[field] = if field == 0x31e { 1 } else { 0 };
        assert!(f.resolve([b"target".as_slice()]).is_err());
    }
    let mut f = Fixture::new(0, 0x10000);
    f.bytes[f.symbols + 24 + 4] = 0x1a; // IFUNC
    assert_eq!(
        f.resolve([b"target".as_slice()]),
        Err(LookupError::Unsupported)
    );
    put64(&mut f.bytes, f.relocations + 8, (1 << 32) | 1025);
    assert_eq!(
        f.resolve([b"target".as_slice()]),
        Err(LookupError::Unsupported)
    );
}

#[test]
fn out_of_range_overflow_and_unterminated_names_fail() {
    for (offset, value) in [
        (0x100 + 8, u64::MAX),
        (0x500, 0xfff8),
        (0x100 + 2 * 16 + 8, u64::MAX),
        (0x100 + 4 * 16 + 8, u64::MAX),
    ] {
        let mut f = Fixture::new(0, 0x10000);
        put64(&mut f.bytes, offset, value);
        assert_eq!(
            f.resolve([b"target".as_slice()]),
            Err(LookupError::OutOfBounds)
        );
    }
    let mut f = Fixture::new(0, 0x10000);
    f.bytes[0x407] = b'x';
    assert_eq!(
        f.resolve([b"target".as_slice()]),
        Err(LookupError::MissingSymbol)
    );
    put32(&mut f.bytes, f.symbols + 24, u32::MAX);
    assert_eq!(
        f.resolve([b"target".as_slice()]),
        Err(LookupError::InvalidTable)
    );
}

#[test]
fn file_slots_need_no_binding_but_alignment_and_version_are_checked() {
    let mut f = Fixture::new(0, 0);
    assert_eq!(
        f.resolve([b"target".as_slice()]),
        Ok([Import { slot: f.slots }])
    );
    put64(&mut f.bytes, f.relocations, f.slots as u64 + 1);
    assert_eq!(
        f.resolve([b"target".as_slice()]),
        Err(LookupError::InvalidTable)
    );
    let mut f = Fixture::new(0, 0x10000);
    // Put version table in the last dynamic entry and terminate in the next entry.
    put64(&mut f.bytes, f.dynamic + 8 * 16, 0x6ffffff0);
    put64(&mut f.bytes, f.dynamic + 8 * 16 + 8, 0x700);
    put16(&mut f.bytes, 0x702, 2);
    let result = resolve_imports(
        |offset, out| {
            out.copy_from_slice(
                f.bytes
                    .get(offset..offset + out.len())
                    .ok_or(LookupError::OutOfBounds)?,
            );
            Ok(())
        },
        (f.dynamic, 10 * 16),
        [b"target".as_slice()],
    );
    assert_eq!(result, Err(LookupError::Unsupported));
}

#[test]
fn all_truncated_inputs_return_errors_without_panics() {
    let f = Fixture::new(0, 0x10000);
    for len in 0..f.bytes.len() {
        let bytes = &f.bytes[..len];
        let result = resolve_imports(
            |offset, out| {
                let end = offset
                    .checked_add(out.len())
                    .ok_or(LookupError::OutOfBounds)?;
                out.copy_from_slice(bytes.get(offset..end).ok_or(LookupError::OutOfBounds)?);
                Ok(())
            },
            (f.dynamic, 9 * 16),
            [b"target".as_slice(), b"other"],
        );
        if len < f.slots + 16 {
            assert!(result.is_err(), "length {len}");
        } else {
            assert!(result.is_ok(), "length {len}");
        }
    }
}
