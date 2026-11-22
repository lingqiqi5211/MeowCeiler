#![forbid(unsafe_code)]

#[path = "../src/lookup.rs"]
mod lookup;

use lookup::*;
use std::io::{self, Cursor, Read, Seek, SeekFrom};

fn image() -> Image {
    let mut image = Image {
        base: 0x1000,
        count: 2,
        ..Image::default()
    };
    image.ranges[0] = Range {
        start: 0x1100,
        end: 0x1200,
        flags: PF_R,
    };
    image.ranges[1] = Range {
        start: 0x1300,
        end: 0x1400,
        flags: PF_R | PF_X,
    };
    image
}

#[test]
fn lookup_requires_contained_range_and_permissions() {
    let image = image();
    assert_eq!(image.dynamic, None);
    assert_eq!(image.address(0x100, 0x100, PF_R), Some(0x1100));
    assert_eq!(image.address(0x300, 0x100, PF_R | PF_X), Some(0x1300));
    assert_eq!(image.address(0x100, 1, PF_R | PF_X), None);
    assert_eq!(image.address(0x100, 0x101, PF_R), None);
    assert_eq!(image.address(0xff, 2, PF_R), None);
    assert_eq!(image.address(0x1ff, 0x102, PF_R), None);
    assert_eq!(image.address(0x200, 1, PF_R), None);
}

#[test]
fn lookup_rejects_overflow_and_invalid_range_counts() {
    let mut image = image();
    assert_eq!(image.address(usize::MAX, 1, PF_R), None);
    assert_eq!(image.address(0x100, usize::MAX, PF_R), None);
    image.count = 0;
    assert_eq!(image.address(0x100, 1, PF_R), None);
    image.count = image.ranges.len() + 1;
    assert_eq!(image.address(0x100, 1, PF_R), None);
}

#[test]
fn maps_lookup_requires_one_private_executable_mapping() {
    let maps = "1000-2000 r-xp 0 0:0 1 /libapp.so\n2000-3000 r-xp 0 0:0 1 /libapp.so";
    assert!(executable_private_page(maps, 0x1000, 0x1000));
    assert!(!executable_private_page(maps, 0x1000, 0x2000));
    assert!(!executable_private_page(maps, 0x800, 0x1000));
    for permissions in ["rw-p", "rwxp", "r-xs", "--xp"] {
        assert!(!executable_private_page(
            &format!("1000-3000 {permissions}"),
            0x1000,
            0x1000
        ));
    }
}

#[test]
fn maps_lookup_rejects_malformed_partial_and_overflow_ranges() {
    for maps in [
        "",
        "invalid",
        "1000-zzzz r-xp",
        "1000-1800 r-xp",
        "3000-1000 r-xp",
    ] {
        assert!(!executable_private_page(maps, 0x1000, 0x1000));
    }
    assert!(!executable_private_page(
        "0-ffffffffffffffff r-xp",
        usize::MAX - 3,
        8
    ));
    assert!(executable_private_page(
        "invalid\n1000-3000 r-xp",
        0x1000,
        0x1000
    ));
}

#[test]
fn fingerprint_lookup_checks_exact_offset_and_bytes_without_writing() {
    let original = b"prefix:api:signature".to_vec();
    let mut file = Cursor::new(original.clone());
    assert!(matches_file_region(&mut file, 7, b"api").unwrap());
    assert!(!matches_file_region(&mut file, 8, b"api").unwrap());
    assert!(!matches_file_region(&mut file, 7, b"API").unwrap());
    assert!(matches_file_region(&mut file, 0, b"prefix").unwrap());
    assert_eq!(file.into_inner(), original);
}

#[test]
fn fingerprint_lookup_rejects_truncated_or_out_of_file_data() {
    let mut file = Cursor::new(b"api");
    assert_eq!(
        matches_file_region(&mut file, 0, b"api-more")
            .unwrap_err()
            .kind(),
        io::ErrorKind::UnexpectedEof
    );
    assert_eq!(
        matches_file_region(&mut file, 100, b"api")
            .unwrap_err()
            .kind(),
        io::ErrorKind::UnexpectedEof
    );
}

#[test]
fn fingerprint_lookup_propagates_seek_failure_without_reading() {
    struct Unseekable;
    impl Seek for Unseekable {
        fn seek(&mut self, _: SeekFrom) -> io::Result<u64> {
            Err(io::Error::new(
                io::ErrorKind::PermissionDenied,
                "seek denied",
            ))
        }
    }
    impl Read for Unseekable {
        fn read(&mut self, _: &mut [u8]) -> io::Result<usize> {
            panic!("must not read after failed seek")
        }
    }
    assert_eq!(
        matches_file_region(&mut Unseekable, 0, b"api")
            .unwrap_err()
            .kind(),
        io::ErrorKind::PermissionDenied
    );
}
