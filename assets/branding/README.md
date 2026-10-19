# OTPBridge identity

The monochrome bridge/keyhole mark was generated through the configured image-generation API on 2026-10-19, visually inspected, converted to exact grayscale and resized into bundled native/extension assets. No network image loads are needed at runtime.

- `otpbridge-logo.png`: 512px source for the shipped icon variants.
- Android launcher variants: `app/android/src/main/res/mipmap-*/ic_brand.png`.
- Chrome icon variants: `extension/public/icons/icon-{16,32,48,128}.png`.
- Native UI actions use bundled ImageVector paths, not Unicode placeholders or icon fonts.

The unprocessed generation is a local ignored artifact, not a runtime dependency. No credentials are stored here.
