#![forbid(unsafe_code)]

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum LookupError {
    InvalidImage,
    Unsupported,
    OutOfBounds,
    InvalidTable,
    MissingSymbol,
    AmbiguousSymbol,
}

#[derive(Clone, Copy, Default, Debug, PartialEq, Eq)]
pub(crate) struct Import {
    pub(crate) slot: usize,
}

fn word(bytes: &[u8]) -> Result<usize, LookupError> {
    let bytes: [u8; 8] = bytes.try_into().map_err(|_| LookupError::InvalidTable)?;
    usize::try_from(u64::from_le_bytes(bytes)).map_err(|_| LookupError::OutOfBounds)
}

fn at(base: usize, index: usize, stride: usize) -> Result<usize, LookupError> {
    base.checked_add(index.checked_mul(stride).ok_or(LookupError::OutOfBounds)?)
        .ok_or(LookupError::OutOfBounds)
}

fn set_once(field: &mut Option<usize>, value: usize) -> Result<(), LookupError> {
    if field.replace(value).is_some() {
        return Err(LookupError::InvalidTable);
    }
    Ok(())
}

// Addresses supplied to read are ELF virtual offsets, not file offsets or process addresses.
// This resolver supports Android ELF64 little-endian AArch64 RELA PLT imports only.
// It locates imports; it does not certify their ABI or the lifetime of the returned addresses.
pub(crate) fn resolve_imports<const N: usize>(
    mut read: impl FnMut(usize, &mut [u8]) -> Result<(), LookupError>,
    dynamic: (usize, usize),
    names: [&[u8]; N],
) -> Result<[Import; N], LookupError> {
    if N == 0
        || names
            .iter()
            .any(|name| name.is_empty() || name.len() > 255 || name.contains(&0))
    {
        return Err(LookupError::InvalidTable);
    }
    for (i, name) in names.iter().enumerate() {
        if names[..i].contains(name) {
            return Err(LookupError::AmbiguousSymbol);
        }
    }
    let mut header = [0; 64];
    read(0, &mut header)?;
    if &header[..7] != b"\x7fELF\x02\x01\x01"
        || u16::from_le_bytes([header[16], header[17]]) != 3
        || u16::from_le_bytes([header[18], header[19]]) != 183
        || u32::from_le_bytes(header[20..24].try_into().unwrap()) != 1
        || u16::from_le_bytes([header[52], header[53]]) != 64
    {
        return Err(LookupError::InvalidImage);
    }
    if dynamic.1 == 0 || dynamic.1 % 16 != 0 || dynamic.1 / 16 > 4096 {
        return Err(LookupError::InvalidTable);
    }
    let (mut strings, mut strings_len, mut symbols, mut symbol_size) = (None, None, None, None);
    let (mut relocations, mut relocations_len, mut relocation_kind) = (None, None, None);
    let (mut versions, mut flags, mut flags_1) = (None, None, None);
    let mut terminated = false;
    for index in 0..dynamic.1 / 16 {
        let mut entry = [0; 16];
        read(at(dynamic.0, index, 16)?, &mut entry)?;
        let tag = word(&entry[..8])?;
        let value = word(&entry[8..])?;
        match tag {
            0 => {
                terminated = true;
                break;
            }
            2 => set_once(&mut relocations_len, value)?,
            5 => set_once(&mut strings, value)?,
            6 => set_once(&mut symbols, value)?,
            10 => set_once(&mut strings_len, value)?,
            11 => set_once(&mut symbol_size, value)?,
            20 => set_once(&mut relocation_kind, value)?,
            23 => set_once(&mut relocations, value)?,
            30 => set_once(&mut flags, value)?,
            0x6ffffff0 => set_once(&mut versions, value)?,
            0x6ffffffb => set_once(&mut flags_1, value)?,
            _ => {}
        }
    }
    if !terminated {
        return Err(LookupError::InvalidTable);
    }
    // Do not treat a lazy resolver trampoline as the callable original.
    if flags.unwrap_or(0) & 8 == 0 && flags_1.unwrap_or(0) & 1 == 0 {
        return Err(LookupError::Unsupported);
    }
    let (
        Some(strings),
        Some(strings_len),
        Some(symbols),
        Some(24),
        Some(relocations),
        Some(relocations_len),
        Some(7),
    ) = (
        strings,
        strings_len,
        symbols,
        symbol_size,
        relocations,
        relocations_len,
        relocation_kind,
    )
    else {
        return Err(LookupError::Unsupported);
    };
    if strings_len == 0
        || strings_len > 32 * 1024 * 1024
        || relocations_len == 0
        || relocations_len % 24 != 0
        || relocations_len / 24 > 131072
    {
        return Err(LookupError::InvalidTable);
    }
    strings
        .checked_add(strings_len)
        .ok_or(LookupError::OutOfBounds)?;
    relocations
        .checked_add(relocations_len)
        .ok_or(LookupError::OutOfBounds)?;
    let mut found = [None; N];
    let name_len = names.iter().map(|name| name.len()).max().unwrap() + 1;
    for index in 0..relocations_len / 24 {
        let mut relocation = [0; 24];
        read(at(relocations, index, 24)?, &mut relocation)?;
        let info = u64::from_le_bytes(relocation[8..16].try_into().unwrap());
        // R_AARCH64_JUMP_SLOT. Other relocation forms require a separate reviewed backend.
        if info as u32 != 1026 {
            return Err(LookupError::Unsupported);
        }
        let symbol = usize::try_from(info >> 32).map_err(|_| LookupError::OutOfBounds)?;
        if symbol == 0 || symbol > 1_048_576 {
            return Err(LookupError::InvalidTable);
        }
        let mut entry = [0; 24];
        read(at(symbols, symbol, 24)?, &mut entry)?;
        let name_offset = u32::from_le_bytes(entry[..4].try_into().unwrap()) as usize;
        let remaining = strings_len
            .checked_sub(name_offset)
            .filter(|remaining| *remaining > 0)
            .ok_or(LookupError::InvalidTable)?;
        let mut name = [0; 256];
        let len = remaining.min(name_len);
        read(at(strings, name_offset, 1)?, &mut name[..len])?;
        for (i, wanted) in names.iter().enumerate() {
            if len <= wanted.len() || &name[..wanted.len()] != *wanted || name[wanted.len()] != 0 {
                continue;
            }
            // Imported, global/weak, function/notype. Do not resolve defined or IFUNC symbols.
            if entry[6..8] != [0, 0]
                || !matches!(entry[4] >> 4, 1 | 2)
                || !matches!(entry[4] & 15, 0 | 2)
                || entry[5] & 3 != 0
                || entry[8..16] != [0; 8]
                || relocation[16..] != [0; 8]
            {
                return Err(LookupError::Unsupported);
            }
            if let Some(versions) = versions {
                let mut version = [0; 2];
                read(at(versions, symbol, 2)?, &mut version)?;
                if u16::from_le_bytes(version) != 1 {
                    return Err(LookupError::Unsupported);
                }
            }
            let slot = word(&relocation[..8])?;
            if slot % 8 != 0 {
                return Err(LookupError::InvalidTable);
            }
            // Validate the complete slot range without interpreting its binding state.
            read(slot, &mut [0; 8])?;
            let import = Import { slot };
            if found[i].replace(import).is_some() {
                return Err(LookupError::AmbiguousSymbol);
            }
        }
    }
    let mut result = [Import::default(); N];
    for (i, import) in found.into_iter().enumerate() {
        result[i] = import.ok_or(LookupError::MissingSymbol)?;
        if result[..i].iter().any(|prior| prior.slot == result[i].slot) {
            return Err(LookupError::AmbiguousSymbol);
        }
    }
    Ok(result)
}
