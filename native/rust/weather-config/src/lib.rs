#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct WeatherConfig {
    pub enabled: bool,
}

impl WeatherConfig {
    pub fn decode(bytes: &[u8]) -> Option<Self> {
        let enabled = match bytes {
            [3, enabled @ 0..=1] => *enabled == 1,
            [1, 0..=1] => false,
            [
                2,
                enabled @ 0..=1,
                0..=3,
                0..=3,
                0..=3,
                0..=6,
                0..=6,
                glass @ ..,
            ] => match glass {
                [] => false,
                [blur @ 0..=2, 0..=2, 0..=2] => *enabled == 1 && *blur == 1,
                _ => return None,
            },
            _ => return None,
        };
        Some(Self { enabled })
    }

    pub fn encode(self) -> [u8; 2] {
        [3, u8::from(self.enabled)]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn top_bar_setting_round_trips() {
        for enabled in [false, true] {
            let config = WeatherConfig { enabled };
            assert_eq!(WeatherConfig::decode(&config.encode()), Some(config));
        }
    }

    #[test]
    fn only_an_explicit_legacy_blur_unlock_is_preserved() {
        for (bytes, expected) in [
            (&[1, 1][..], false),
            (&[2, 1, 3, 3, 3, 6, 6][..], false),
            (&[2, 1, 3, 3, 3, 6, 6, 0, 1, 1][..], false),
            (&[2, 1, 3, 3, 3, 6, 6, 2, 1, 1][..], false),
            (&[2, 0, 0, 0, 0, 0, 0, 1, 0, 0][..], false),
            (&[2, 1, 0, 0, 0, 0, 0, 1, 0, 0][..], true),
        ] {
            assert_eq!(
                WeatherConfig::decode(bytes),
                Some(WeatherConfig { enabled: expected })
            );
        }
        for bytes in [
            &[3, 2][..],
            &[2, 1][..],
            &[2, 1, 4, 0, 0, 0, 0][..],
            &[2, 1, 0, 0, 0, 0, 0, 1, 3, 0][..],
        ] {
            assert_eq!(WeatherConfig::decode(bytes), None);
        }
    }
}
