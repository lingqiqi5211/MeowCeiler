#![forbid(unsafe_code)]

use std::io::{self, Read, Seek, SeekFrom};

pub(crate) const PF_R: u32 = 4;
pub(crate) const PF_X: u32 = 1;
#[derive(Clone, Copy, Default)]
pub(crate) struct Range {
    pub(crate) start: usize,
    pub(crate) end: usize,
    pub(crate) flags: u32,
}

#[derive(Clone, Copy, Default)]
pub(crate) struct Image {
    pub(crate) base: usize,
    pub(crate) ranges: [Range; 8],
    pub(crate) count: usize,
    pub(crate) dynamic: Option<(usize, usize)>,
}

impl Image {
    pub(crate) fn address(&self, offset: usize, len: usize, flags: u32) -> Option<usize> {
        let start = self.base.checked_add(offset)?;
        let end = start.checked_add(len)?;
        self.ranges
            .get(..self.count)?
            .iter()
            .any(|range| range.start <= start && end <= range.end && range.flags & flags == flags)
            .then_some(start)
    }
}

pub(crate) fn executable_private_page(maps: &str, page: usize, size: usize) -> bool {
    let Some(end) = page.checked_add(size) else {
        return false;
    };
    maps.lines().any(|line| {
        let mut fields = line.split_whitespace();
        let Some((start, limit)) = fields.next().and_then(|s| s.split_once('-')) else {
            return false;
        };
        let (Ok(start), Ok(limit)) = (
            usize::from_str_radix(start, 16),
            usize::from_str_radix(limit, 16),
        ) else {
            return false;
        };
        fields.next() == Some("r-xp") && start <= page && end <= limit
    })
}

pub(crate) fn matches_file_region(
    file: &mut (impl Read + Seek),
    offset: u64,
    expected: &[u8],
) -> io::Result<bool> {
    let mut bytes = vec![0; expected.len()];
    file.seek(SeekFrom::Start(offset))?;
    file.read_exact(&mut bytes)?;
    Ok(bytes == expected)
}
