# Contributing

Contributions are welcome for the Android sender, the Linux OBS receiver, the
wire protocol, and the surrounding documentation.

Before opening a pull request:

1. Read [Architecture](docs/architecture.md) when changing the streaming
   pipeline.
2. Follow [Protocol](docs/protocol.md) when changing anything on the wire.
3. Run `./scripts/development/check-all.sh` when the required toolchains are
   available.
4. Explain the platform, Android device or emulator, OBS version, and decoder
   path used for testing.
5. Do not commit credentials, signing keys, local deployment configuration,
   generated build output, or private workspace files.

Keep compatibility-sensitive protocol identifiers stable unless a protocol
change is intentional and documented in the protocol fixtures.
