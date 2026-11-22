use std::io::{self, ErrorKind};

pub const MAX_FRAME: usize = 4096;
pub const REQUEST: u16 = 1;
/// Target names followed by one connected target socket FD; no caller-supplied context.
pub const TARGET_REQUEST: u16 = 4;
pub const OFFER: u16 = 2;
pub const REJECTED: u16 = 3;
pub const MAGIC: [u8; 4] = *b"NRL1";
pub const WIRE_VERSION: u16 = 1;

pub fn invalid(message: &'static str) -> io::Error {
    io::Error::new(ErrorKind::InvalidData, message)
}

#[derive(Debug, PartialEq, Eq)]
pub struct Target {
    pub package: String,
    pub process: String,
}

impl Target {
    pub fn encode(&self) -> io::Result<Vec<u8>> {
        let mut out = Vec::new();
        for value in [&self.package, &self.process] {
            if value.is_empty() || value.len() > 255 || value.as_bytes().contains(&0) {
                return Err(invalid("invalid target name"));
            }
            out.extend_from_slice(&(value.len() as u16).to_le_bytes());
            out.extend_from_slice(value.as_bytes());
        }
        Ok(out)
    }

    pub fn decode(bytes: &[u8]) -> io::Result<Self> {
        let mut input = bytes;
        let package = take_name(&mut input)?;
        let process = take_name(&mut input)?;
        if !input.is_empty() {
            return Err(invalid("trailing target data"));
        }
        Ok(Self { package, process })
    }
}

fn take_name(input: &mut &[u8]) -> io::Result<String> {
    if input.len() < 2 {
        return Err(invalid("truncated target name"));
    }
    let len = u16::from_le_bytes([input[0], input[1]]) as usize;
    *input = &input[2..];
    if len == 0 || len > 255 || input.len() < len || input[..len].contains(&0) {
        return Err(invalid("invalid target name"));
    }
    let value = std::str::from_utf8(&input[..len])
        .map_err(|_| invalid("target is not UTF-8"))?
        .to_owned();
    *input = &input[len..];
    Ok(value)
}

#[derive(Debug, PartialEq, Eq)]
pub struct Offer {
    pub payload_revision: u64,
    pub config_revision: u64,
    pub config: Vec<u8>,
}

impl Offer {
    pub fn encode(&self) -> io::Result<Vec<u8>> {
        if self.payload_revision == 0
            || self.config_revision == 0
            || self.config.len() > MAX_FRAME - 16
        {
            return Err(invalid("invalid offer"));
        }
        let mut bytes = Vec::with_capacity(16 + self.config.len());
        bytes.extend_from_slice(&self.payload_revision.to_le_bytes());
        bytes.extend_from_slice(&self.config_revision.to_le_bytes());
        bytes.extend_from_slice(&self.config);
        Ok(bytes)
    }

    pub fn decode(bytes: &[u8]) -> io::Result<Self> {
        if bytes.len() < 16 || bytes.len() > MAX_FRAME {
            return Err(invalid("invalid offer size"));
        }
        let offer = Self {
            payload_revision: u64::from_le_bytes(bytes[..8].try_into().unwrap()),
            config_revision: u64::from_le_bytes(bytes[8..16].try_into().unwrap()),
            config: bytes[16..].to_vec(),
        };
        if offer.payload_revision == 0 || offer.config_revision == 0 {
            return Err(invalid("zero revision"));
        }
        Ok(offer)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn target_is_exact_and_rejects_ambiguous_encodings() {
        let target = Target {
            package: "example.app".into(),
            process: "example.app:worker".into(),
        };
        let bytes = target.encode().unwrap();
        assert_eq!(Target::decode(&bytes).unwrap(), target);
        for n in 0..bytes.len() {
            assert!(Target::decode(&bytes[..n]).is_err());
        }
        let mut trailing = bytes.clone();
        trailing.push(0);
        assert!(Target::decode(&trailing).is_err());
        assert!(
            Target {
                package: "a\0b".into(),
                process: "a".into()
            }
            .encode()
            .is_err()
        );
    }

    #[test]
    fn offer_validates_revisions_and_bounds() {
        let offer = Offer {
            payload_revision: 1,
            config_revision: 2,
            config: vec![0, 1, 2],
        };
        assert_eq!(Offer::decode(&offer.encode().unwrap()).unwrap(), offer);
        assert!(Offer::decode(&[0; 16]).is_err());
        assert!(Offer::decode(&[0; 15]).is_err());
        assert!(
            Offer {
                config: vec![0; MAX_FRAME],
                ..offer
            }
            .encode()
            .is_err()
        );
    }
}
