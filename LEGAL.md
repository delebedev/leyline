# Legal Notice

## Disclaimer

Leyline is an independent, open-source project. It is **not** produced, endorsed, supported, or affiliated with Wizards of the Coast, Hasbro, or Card-Forge.

Magic: The Gathering, MTG Arena, and all related trademarks, card names, and card images are the property of Wizards of the Coast LLC, a subsidiary of Hasbro, Inc.

## What this project is

Leyline is a local server that implements compatibility with the MTG Arena network protocol. It uses the [Forge](https://github.com/Card-Forge/forge) open-source rules engine (GPL-3.0) to process game logic.

The protocol implementation is an independent interoperability effort based on project-authored code and documentation.

## What this project does NOT contain

- No MTG Arena client binaries
- No Wizards of the Coast proprietary assets (art, sounds, textures, fonts)
- No card images or copyrighted visual content
- No Arena client source code (decompiled or otherwise)

Users must obtain the MTG Arena client independently from Wizards of the Coast. The client's local card database is read at runtime but is never copied, modified, or redistributed.

## Protocol definitions

Protobuf message definitions are sourced from [riQQ/MtgaProto](https://github.com/riQQ/MtgaProto), a third-party project that publishes protocol schemas derived from the publicly available client.

## License

This project is licensed under the GNU General Public License v3.0 (GPL-3.0), inherited from the Forge engine. See [LICENSE](LICENSE) for the full text.

## No monetization

Leyline is and will remain free. There is no paid tier, no premium access, no donation-gated features, and no commercial use.

## User risk

Using this tool may violate your agreement with the game publisher. Use at your own risk.

## Contact

To report a legal concern, contact the project maintainer directly via the repository's issue tracker.
