# Security and sensitive data

Do not commit Android signing keys, CarPlay authentication assets, pairing records or exported diagnostic reports. The repository's ordinary source build does not include runtime authentication files; a standalone build requires them as an explicit local input.

Anyone receiving an APK can extract assets packaged in it. Obfuscation and a private source repository do not protect an embedded key. Distribute only assets you are authorized to use.

Review diagnostic reports before sharing them. If a report or issue contains credentials or personal information, remove that material before posting publicly.
